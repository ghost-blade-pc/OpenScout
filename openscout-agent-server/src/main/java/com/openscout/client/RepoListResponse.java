package com.openscout.client;

import java.util.List;

public record RepoListResponse(List<RepoSummary> items) {

    public List<RepoSummary> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}
