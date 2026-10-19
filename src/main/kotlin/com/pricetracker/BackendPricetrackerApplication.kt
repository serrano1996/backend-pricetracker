package com.pricetracker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BackendPricetrackerApplication

fun main(args: Array<String>) {
	runApplication<BackendPricetrackerApplication>(*args)
}
