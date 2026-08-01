package dev.subnetory.dto;

import java.util.List;

public record AvailableIpResponse(
        String network,
        int requested,
        int found,
        List<String> availableIps
) {}
