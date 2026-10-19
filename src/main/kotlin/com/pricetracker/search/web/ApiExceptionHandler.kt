package com.pricetracker.search.web

import com.pricetracker.search.application.AllStoresUnavailableException
import com.pricetracker.search.domain.InvalidSearchQueryException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidSearchQueryException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidQuery(e: InvalidSearchQueryException) = ErrorResponse(e.message ?: "Invalid search")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun missingParameter(e: MissingServletRequestParameterException) = ErrorResponse("${e.parameterName} is required")

    @ExceptionHandler(AllStoresUnavailableException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun allStoresUnavailable(e: AllStoresUnavailableException) = ErrorResponse("All stores are unavailable")
}
