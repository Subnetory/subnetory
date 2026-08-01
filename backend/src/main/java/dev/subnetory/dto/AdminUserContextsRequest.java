package dev.subnetory.dto;

import java.util.Set;

public record AdminUserContextsRequest(Set<Long> contextIds) {}
