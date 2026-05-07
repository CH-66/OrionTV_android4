package com.oriontv.legacy.api.models;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class SearchResult {
    public String id;
    public String title;
    public String poster;
    public List<String> episodes = new ArrayList<String>();
    public String source;
    public String source_name;
    @SerializedName("class")
    public String className;
    public String year;
    public String desc;
    public String type_name;
    public String resolution;
}
