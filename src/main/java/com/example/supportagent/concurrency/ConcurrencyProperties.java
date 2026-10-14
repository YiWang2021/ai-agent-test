package com.example.supportagent.concurrency;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("support.concurrency")
public record ConcurrencyProperties(
        @DefaultValue("16") @Min(1) int instanceLimit,
        @DefaultValue("32") @Min(1) int globalLimit,
        @DefaultValue("16") @Min(1) int tenantLimit,
        @DefaultValue("2") @Min(1) int userLimit,
        @DefaultValue("120") @Min(1) int userRequestsPerMinute,
        @DefaultValue("30") @Min(3) int leaseSeconds,
        @DefaultValue("default") @Pattern(regexp="[A-Za-z0-9_-]{1,60}") String namespace) {}

