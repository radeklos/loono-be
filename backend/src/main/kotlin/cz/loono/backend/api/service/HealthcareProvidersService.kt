package cz.loono.backend.api.service

import com.google.gson.Gson
import cz.loono.backend.api.dto.HealthcareProviderDetailDto
import cz.loono.backend.api.dto.HealthcareProviderDetailListDto
import cz.loono.backend.api.dto.HealthcareProviderIdDto
import cz.loono.backend.api.dto.HealthcareProviderIdListDto
import cz.loono.backend.api.dto.SimpleHealthcareProviderDto
import cz.loono.backend.api.dto.UpdateStatusMessageDto
import cz.loono.backend.api.exception.LoonoBackendException
import cz.loono.backend.data.HealthcareCSVParser
import cz.loono.backend.data.constants.CategoryValues
import cz.loono.backend.data.constants.Constants.OPEN_DATA_URL
import cz.loono.backend.db.model.HealthcareCategory
import cz.loono.backend.db.model.HealthcareProvider
import cz.loono.backend.db.model.HealthcareProviderId
import cz.loono.backend.db.model.ServerProperties
import cz.loono.backend.db.repository.HealthcareCategoryRepository
import cz.loono.backend.db.repository.HealthcareProviderRepository
import cz.loono.backend.db.repository.ServerPropertiesRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

@Service
class HealthcareProvidersService @Autowired constructor(
    private val healthcareProviderRepository: HealthcareProviderRepository,
    private val healthcareCategoryRepository: HealthcareCategoryRepository,
    private val serverPropertiesRepository: ServerPropertiesRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val batchSize = 500
    private var updating = false
    private var zipFilePath = Path.of("init")
    var lastUpdate = "not initialized"

    @Scheduled(cron = "0 0 2 2 * ?") // each the 2nd day of month at 2AM
    @Synchronized
    fun updateData(): UpdateStatusMessageDto {
        updating = true
        val input = URL(OPEN_DATA_URL).openStream()
        val providers = HealthcareCSVParser().parse(input)
        if (providers.isNotEmpty()) {
            updating = true
            try {
                saveCategories()
                saveProviders(providers)
                setLastUpdate()
                prepareAllProviders()
            } finally {
                updating = false
            }
        } else {
            throw LoonoBackendException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                errorCode = HttpStatus.UNPROCESSABLE_ENTITY.value().toString(),
                errorMessage = "Data update failed."
            )
        }
        logger.info("Update finished.")
        return UpdateStatusMessageDto("Data successfully updated.")
    }

    @Synchronized
    fun saveProviders(providers: List<HealthcareProvider>) {
        if (providers.isEmpty()) {
            return
        }
        val cycles = providers.size.div(batchSize)
        val rest = providers.size % batchSize - 1
        for (i in 0..cycles) {
            val start = i * batchSize
            var end = start + 499
            if (i == cycles) {
                end = start + rest
            }
            saveProvidersBatch(providers.subList(start, end))
        }
    }

    @Synchronized
    @Transactional
    fun saveProvidersBatch(providersSublist: List<HealthcareProvider>) {
        healthcareProviderRepository.saveAll(providersSublist)
    }

    @Synchronized
    @Transactional
    fun saveCategories() {
        val categoryValues = CategoryValues.values().map { HealthcareCategory(value = it.value) }
        healthcareCategoryRepository.saveAll(categoryValues)
    }

    @Synchronized
    @Transactional(rollbackFor = [Exception::class])
    fun setLastUpdate() {
        val serverProperties = serverPropertiesRepository.findAll()
        val updateDate = LocalDate.now()
        lastUpdate = "${updateDate.year}-${updateDate.monthValue}-${updateDate.dayOfMonth}"
        if (serverProperties.isEmpty()) {
            serverPropertiesRepository.save(ServerProperties())
            return
        }
        val firstProperties = serverProperties.first()
        firstProperties.lastUpdate = updateDate
        serverPropertiesRepository.save(firstProperties)
    }

    @Synchronized
    fun prepareAllProviders() {
        val providers = createDefaultProvidersSet()
        if (providers.isNotEmpty()) {
            val cycles = providers.size.div(batchSize)
            for (i in 0..cycles) {
                providers.addAll(findPage(i))
            }
        }
        zipProviders(providers)
    }

    @Synchronized
    @Transactional(readOnly = true)
    fun createDefaultProvidersSet(): LinkedHashSet<SimpleHealthcareProviderDto> {
        val count = healthcareProviderRepository.count().toInt()
        return LinkedHashSet(count)
    }

    @Synchronized
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun findPage(page: Int): Page<SimpleHealthcareProviderDto> {
        return healthcareProviderRepository.findAll(PageRequest.of(page, batchSize)).map { it.simplify() }
    }

    @Synchronized
    private fun zipProviders(providers: LinkedHashSet<SimpleHealthcareProviderDto>) {
        if (!zipFilePath.endsWith("init") && zipFilePath.exists()) {
            Files.delete(zipFilePath)
        }
        val filePath = Path.of("providers-$lastUpdate.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(File(filePath.toUri())))).use { zip ->
                OutputStreamWriter(zip).use { writer ->
                    zip.putNextEntry(ZipEntry("providers.json"))
                    Gson().toJson(providers, writer)
                }
            }
        } catch (ioe: IOException) {
            throw LoonoBackendException(
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                errorCode = "422",
                errorMessage = "The file cannot be downloaded."
            )
        }
        zipFilePath = filePath
    }

    fun getAllSimpleData(): Path {
        if (updating) {
            throw throw LoonoBackendException(
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                errorCode = "422",
                errorMessage = "Server is updating data."
            )
        }
        return zipFilePath
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun getHealthcareProviderDetail(healthcareProviderId: HealthcareProviderIdDto): HealthcareProviderDetailDto {
        val provider = healthcareProviderRepository.findByIdOrNull(
            HealthcareProviderId(
                locationId = healthcareProviderId.locationId,
                institutionId = healthcareProviderId.institutionId
            )
        )
        return provider?.getDetails() ?: throw LoonoBackendException(
            status = HttpStatus.NOT_FOUND,
            errorCode = "404",
            errorMessage = "The healthcare provider with this ID not found."
        )
    }

    fun getMultipleHealthcareProviderDetails(providerIdsList: HealthcareProviderIdListDto): HealthcareProviderDetailListDto {
        return HealthcareProviderDetailListDto(
            healthcareProvidersDetails = providerIdsList.providersIds?.map {
                getHealthcareProviderDetail(it)
            }
        )
    }

    fun HealthcareProvider.simplify(): SimpleHealthcareProviderDto {
        return SimpleHealthcareProviderDto(
            locationId = locationId,
            institutionId = institutionId,
            title = title,
            street = street,
            houseNumber = houseNumber,
            city = city,
            postalCode = postalCode,
            category = category.map { it.value },
            specialization = specialization,
            lat = lat,
            lng = lng
        )
    }

    fun HealthcareProvider.getDetails(): HealthcareProviderDetailDto {
        return HealthcareProviderDetailDto(
            locationId = locationId,
            institutionId = institutionId,
            title = title,
            institutionType = institutionType,
            street = street,
            houseNumber = houseNumber,
            city = city,
            postalCode = postalCode,
            phoneNumber = phoneNumber,
            fax = fax,
            email = email,
            website = website,
            ico = ico,
            category = category.map { it.value },
            specialization = specialization,
            careForm = careForm,
            careType = careType,
            substitute = substitute,
            lat = lat,
            lng = lng
        )
    }
}
