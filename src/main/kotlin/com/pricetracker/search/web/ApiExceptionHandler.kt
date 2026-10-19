package com.pricetracker.search.web

import com.pricetracker.history.application.ProductNotFoundException
import com.pricetracker.search.application.AllStoresUnavailableException
import com.pricetracker.search.domain.InvalidSearchQueryException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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

    @ExceptionHandler(ProductNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun productNotFound(e: ProductNotFoundException) = ErrorResponse("Product not found")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(e: MethodArgumentTypeMismatchException) =
        ErrorResponse(if (e.requiredType == java.util.UUID::class.java) "${e.name} must be a UUID" else "${e.name} is invalid")
}
