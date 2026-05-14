package com.farstars.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DuPan {

    protected String action;
    protected String token;
    @JsonProperty("fs_id")
    protected Long fsId;
    protected String filename;
    protected String dir;

    }

