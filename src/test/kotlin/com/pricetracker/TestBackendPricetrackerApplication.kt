package com.pricetracker

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<BackendPricetrackerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
