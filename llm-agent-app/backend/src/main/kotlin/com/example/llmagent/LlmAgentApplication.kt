package com.example.llmagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LlmAgentApplication

fun main(args: Array<String>) {
    runApplication<LlmAgentApplication>(*args)
}
