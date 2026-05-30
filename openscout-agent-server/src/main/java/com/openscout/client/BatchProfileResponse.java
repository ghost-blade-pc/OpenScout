package com.openscout.client;

import java.util.List;

public record BatchProfileResponse(List<RepoSummary> items, List<RepoError> errors) {

    public List<RepoSummary> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }

    public List<RepoError> errorsOrEmpty() {
        return errors == null ? List.of() : errors;
    }
}
