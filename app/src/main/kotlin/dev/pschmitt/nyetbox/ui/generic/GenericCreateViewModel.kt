package dev.pschmitt.nyetbox.ui.generic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.repository.CreateChoice
import dev.pschmitt.nyetbox.data.repository.CreateFieldDefinition
import dev.pschmitt.nyetbox.data.repository.CreateSubmission
import dev.pschmitt.nyetbox.data.repository.CustomFieldDefinition
import dev.pschmitt.nyetbox.data.repository.CustomFieldRepository
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DeviceTypeRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.buildCreateBody
import dev.pschmitt.nyetbox.data.repository.createChoiceSearchFields
import dev.pschmitt.nyetbox.data.repository.fallbackCreateFieldDefinitions
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.ui.navigation.Route
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@HiltViewModel
class GenericCreateViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GenericObjectRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceTypeRepository: DeviceTypeRepository,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val pendingEditRepository: PendingEditRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    val route: Route.GenericCreate = savedStateHandle.toRoute()

    private val _fields = MutableStateFlow<List<CreateFieldDefinition>>(emptyList())
    val fields: StateFlow<List<CreateFieldDefinition>> = _fields.asStateFlow()

    private val _referenceOptions = MutableStateFlow<Map<String, List<CreateChoice>>>(emptyMap())
    val referenceOptions: StateFlow<Map<String, List<CreateChoice>>> =
        _referenceOptions.asStateFlow()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _createdId = MutableStateFlow<Int?>(null)
    val createdId: StateFlow<Int?> = _createdId.asStateFlow()
    private val _createdDisplay = MutableStateFlow<String?>(null)
    val createdDisplay: StateFlow<String?> = _createdDisplay.asStateFlow()

    init {
        viewModelScope.launch {
            val customDefinitions = customFieldRepository.observeDefinitions().first()
            val fieldDefinitions =
                if (settingsRepository.offlineMode.value) {
                    Result.success(emptyList())
                } else {
                    repository.createFieldDefinitions(route.endpointPath)
                }
            fieldDefinitions
                .onSuccess { definitions ->
                    val fallback = fallbackCreateFieldDefinitions(route.endpointPath)
                    if (definitions.isEmpty() && fallback.isNotEmpty()) {
                        _errorMessage.value =
                            if (settingsRepository.offlineMode.value) {
                                "Offline mode: using the cached core fields"
                            } else {
                                "NetBox did not provide form metadata; using the core fields"
                            }
                        initializeFields(withCustomFields(fallback, customDefinitions))
                    } else {
                        initializeFields(withCustomFields(definitions, customDefinitions))
                    }
                }
                .onFailure {
                    val fallback = fallbackCreateFieldDefinitions(route.endpointPath)
                    if (fallback.isEmpty()) {
                        _errorMessage.value = it.message ?: "Couldn't load the creation form"
                    } else {
                        _errorMessage.value =
                            "NetBox did not provide form metadata; using the core fields"
                        initializeFields(withCustomFields(fallback, customDefinitions))
                    }
                }
            _isLoading.value = false
        }
    }

    fun setValue(key: String, value: String) {
        _values.value = _values.value + (key to value)
    }

    fun create() {
        if (_isSaving.value) return
        buildCreateBody(_fields.value, _values.value)
            .onFailure { _errorMessage.value = it.message }
            .onSuccess { body ->
                viewModelScope.launch {
                    _isSaving.value = true
                    pendingEditRepository
                        .submitCreate(
                            endpointPath = route.endpointPath,
                            body = body,
                            offline = settingsRepository.offlineMode.value,
                        )
                        .onSuccess { objectJson ->
                            val submission = objectJson
                            val createdObject =
                                when (submission) {
                                    is CreateSubmission.Created -> submission.objectJson
                                    is CreateSubmission.Queued -> submission.objectJson
                                }
                            val id =
                                (createdObject["id"] as? JsonPrimitive)
                                    ?.contentOrNull
                                    ?.toIntOrNull()
                            if (id == null) {
                                _errorMessage.value = "The created item has no numeric ID"
                            } else {
                                if (route.endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH) {
                                    customFieldRepository.cacheDefinition(createdObject)
                                }
                                _createdDisplay.value =
                                    sequenceOf("display", "name", "label", "model", "serial")
                                        .mapNotNull {
                                            (createdObject[it] as? JsonPrimitive)?.contentOrNull
                                        }
                                        .firstOrNull { it.isNotBlank() }
                                _createdId.value = id
                                if (submission is CreateSubmission.Created) {
                                    when (route.endpointPath) {
                                        "api/dcim/devices/" -> deviceRepository.refreshDevice(id)
                                        "api/dcim/device-types/" -> deviceTypeRepository.refresh(id)
                                    }
                                    syncScheduler.syncNow()
                                } else if (!settingsRepository.offlineMode.value) {
                                    // A connection may have disappeared between the form and the
                                    // POST. Keep a constrained one-shot worker waiting for it to
                                    // return instead of relying only on the six-hour periodic run.
                                    syncScheduler.syncNow()
                                }
                            }
                        }
                        .onFailure {
                            _errorMessage.value = it.message ?: "Couldn't create ${route.label}"
                        }
                    _isSaving.value = false
                }
            }
    }

    fun errorShown() {
        _errorMessage.value = null
    }

    private suspend fun loadReferenceOptions(definitions: List<CreateFieldDefinition>) {
        val deviceTypeImages =
            if (definitions.any { it.referenceEndpointPath == "api/dcim/device-types/" }) {
                deviceTypeRepository.cachedAll().associateBy { it.id }
            } else {
                emptyMap()
            }
        val options = buildMap {
            definitions
                .filter { it.referenceEndpointPath != null }
                .forEach { field ->
                    val values =
                        repository.cachedObjects(field.referenceEndpointPath!!).map { objectEntity
                            ->
                            val images =
                                if (field.referenceEndpointPath == "api/dcim/device-types/") {
                                    deviceTypeImages[objectEntity.id]
                                } else {
                                    null
                                }
                            CreateChoice(
                                value = objectEntity.id.toString(),
                                label = objectEntity.display,
                                frontImageUrl = images?.frontImageUrl,
                                rearImageUrl = images?.rearImageUrl,
                                searchFields =
                                    runCatching {
                                            json.decodeFromString(
                                                JsonObject.serializer(),
                                                objectEntity.json,
                                            )
                                        }
                                        .getOrNull()
                                        ?.createChoiceSearchFields()
                                        .orEmpty(),
                            )
                        }
                    val parentDevice =
                        if (
                            field.referenceEndpointPath == "api/dcim/devices/" &&
                                route.parentDeviceId != null
                        ) {
                            deviceRepository.observeDevice(route.parentDeviceId).first()
                        } else {
                            null
                        }
                    val withParent =
                        if (
                            parentDevice != null &&
                                values.none { it.value == parentDevice.id.toString() }
                        ) {
                            values + CreateChoice(parentDevice.id.toString(), parentDevice.name)
                        } else {
                            values
                        }
                    if (withParent.isNotEmpty()) put(field.key, withParent)
                }
            if (
                route.endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH &&
                    definitions.any { it.key == "object_types" }
            ) {
                repository
                    .cachedContentTypeChoices()
                    .takeIf { it.isNotEmpty() }
                    ?.let { put("object_types", it) }
            }
        }
        _referenceOptions.value = options
    }

    private fun initializeFields(definitions: List<CreateFieldDefinition>) {
        _fields.value = definitions
        _values.value = definitions.associate { field ->
            field.key to
                when {
                    route.parentDeviceId != null && field.key in setOf("device", "device_id") ->
                        route.parentDeviceId.toString()
                    else -> field.defaultValue.asFormValue()
                }
        }
        viewModelScope.launch { loadReferenceOptions(definitions) }
        viewModelScope.launch { loadCustomChoices(definitions) }
    }

    private suspend fun loadCustomChoices(definitions: List<CreateFieldDefinition>) {
        if (settingsRepository.offlineMode.value) return
        val customDefinitions =
            customFieldRepository.observeDefinitions().first().associateBy { it.name }
        val choices = buildMap {
            definitions
                .filter {
                    it.customFieldName != null &&
                        it.type in setOf("select", "choice", "multiselect", "multi-select")
                }
                .forEach { field ->
                    val name = field.customFieldName ?: return@forEach
                    customDefinitions[name]?.let { definition ->
                        put(field.key, customFieldRepository.choicesFor(definition))
                    }
                }
        }
        if (choices.isNotEmpty()) {
            _fields.value =
                _fields.value.map { field ->
                    field.copy(choices = choices[field.key].orEmpty().ifEmpty { field.choices })
                }
        }
    }

    private fun withCustomFields(
        definitions: List<CreateFieldDefinition>,
        customDefinitions: List<CustomFieldDefinition>,
    ): List<CreateFieldDefinition> {
        val target = route.endpointPath.toObjectType()
        val customFields =
            customDefinitions
                .filter { definition ->
                    target == null ||
                        definition.objectTypes.isEmpty() ||
                        target in definition.objectTypes
                }
                .sortedWith(
                    compareBy<CustomFieldDefinition>(
                        { it.group.orEmpty() },
                        { it.weight },
                        { it.label ?: it.name },
                    )
                )
                .map { definition ->
                    val type = definition.type.lowercase()
                    CreateFieldDefinition(
                        key = "custom_fields.${definition.name}",
                        label = definition.label?.takeIf { it.isNotBlank() } ?: definition.name,
                        type = type,
                        required = false,
                        defaultValue = null,
                        choices = emptyList(),
                        referenceEndpointPath = null,
                        customFieldName = definition.name,
                        markdown = type in setOf("markdown", "longtext"),
                        multiple =
                            type in
                                setOf(
                                    "multiselect",
                                    "multi-select",
                                    "multiple-choice",
                                    "multiple_choice",
                                    "multiple-object",
                                ),
                    )
                }
        return (definitions.filterNot { it.key == "custom_fields" } + customFields).distinctBy {
            it.key
        }
    }

    private fun String.toObjectType(): String? {
        val parts = removePrefix("api/").trim('/').split('/')
        if (parts.size < 2) return null
        val model = parts.last().removeSuffix("s").replace("-", "")
        return "${parts.first()}.$model"
    }

    private fun kotlinx.serialization.json.JsonElement?.asFormValue(): String =
        when (this) {
            null,
            is kotlinx.serialization.json.JsonNull -> ""
            is JsonPrimitive -> contentOrNull.orEmpty()
            else -> toString()
        }

    private companion object {
        const val CUSTOM_FIELDS_ENDPOINT_PATH = "api/extras/custom-fields/"
    }
}
