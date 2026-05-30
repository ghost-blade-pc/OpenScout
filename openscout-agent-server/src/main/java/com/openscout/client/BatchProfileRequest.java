package com.openscout.client;

import java.util.List;

public record BatchProfileRequest(List<String> repos) {
}
