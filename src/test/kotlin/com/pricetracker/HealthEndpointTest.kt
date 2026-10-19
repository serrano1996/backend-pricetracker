package com.pricetracker

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `health is public and hides details`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components") { doesNotExist() }
            jsonPath("$.details") { doesNotExist() }
        }
    }

    @Test
    fun `other actuator endpoints are not exposed`() {
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
    }
}
