package com.pricetracker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BackendPricetrackerApplication

fun main(args: Array<String>) {
	runApplication<BackendPricetrackerApplication>(*args)
}
