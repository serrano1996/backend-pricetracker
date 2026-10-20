package com.pricetracker.search.infrastructure.geo

import com.pricetracker.search.domain.Coordinates
import com.pricetracker.search.domain.PostalCodeLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource

@Configuration
class PostalCodeLocatorConfig {

    /** GeoNames postal codes (CC BY 4.0), one averaged point per code; how to regenerate it in spec 006 plan. */
    @Bean
    fun postalCodeLocator(): PostalCodeLocator {
        val coordinates = ClassPathResource("geo/postal-codes-es.csv").inputStream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val (code, latitude, longitude) = line.split(',')
                code to Coordinates(latitude.toDouble(), longitude.toDouble())
            }
        }
        return PostalCodeLocator(coordinates)
    }
}
