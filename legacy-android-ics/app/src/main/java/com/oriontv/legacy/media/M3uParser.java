package com.oriontv.legacy.media;

import com.oriontv.legacy.api.models.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uParser {
    private static final Pattern LOGO = Pattern.compile("tvg-logo=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    public List<Channel> parse(String text) {
        ArrayList<Channel> channels = new ArrayList<Channel>();
        if (text == null) return channels;

        String[] lines = text.split("\\r?\\n");
        Channel pending = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXTINF:")) {
                pending = new Channel();
                int comma = line.lastIndexOf(',');
                pending.name = comma >= 0 ? line.substring(comma + 1).trim() : "Unknown";
                pending.logo = match(LOGO, line);
                pending.group = match(GROUP, line);
                if (pending.group == null || pending.group.length() == 0) pending.group = "Default";
            } else if (pending != null && line.length() > 0 && !line.startsWith("#") && line.indexOf("://") > 0) {
                pending.url = line;
                pending.id = line;
                channels.add(pending);
                pending = null;
            }
        }
        return channels;
    }

    private String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
