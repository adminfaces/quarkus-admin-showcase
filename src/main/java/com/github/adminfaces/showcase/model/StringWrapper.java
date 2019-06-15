package com.github.adminfaces.showcase.model;

import java.io.Serializable;

public class StringWrapper implements Serializable {

    private String value;

    public StringWrapper() {
    }

    public StringWrapper(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
