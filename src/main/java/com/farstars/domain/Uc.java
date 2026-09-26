package com.farstars.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Uc {
    protected String action;
    protected String cookie;
    @JsonProperty("fs_id")
    protected String fsId;
    protected String filename;
    protected String dir;
}
