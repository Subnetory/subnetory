package dev.subnetory.web.form;

import java.util.HashSet;
import java.util.Set;

public class UserContextForm {

    private Set<Long> contextIds = new HashSet<>();

    public Set<Long> getContextIds() {
        return contextIds;
    }

    public void setContextIds(Set<Long> contextIds) {
        this.contextIds = contextIds == null ? new HashSet<>() : contextIds;
    }
}
