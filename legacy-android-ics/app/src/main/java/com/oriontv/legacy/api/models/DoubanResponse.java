package com.oriontv.legacy.api.models;

import java.util.ArrayList;
import java.util.List;

public class DoubanResponse {
    public int code;
    public String message;
    public List<DoubanItem> list = new ArrayList<DoubanItem>();
}
