package com.retrofrost.cubicalcompare.v5;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** M3U preserves creator-defined order and duplicates, never embeds audio. */
public final class M3uPattern {
    private M3uPattern() {}
    public static String save(List<String> tracks) {
        if(tracks.size()>128)throw new IllegalArgumentException("Maximum 128 audio tracks.");
        StringBuilder b=new StringBuilder("#EXTM3U\r\n");
        for(String path:tracks) {
            if(path.contains("\n")||path.contains("\r"))throw new IllegalArgumentException("Newline in audio reference");
            b.append("#EXTINF:-1,").append(label(path)).append("\r\n");
            b.append(path).append("\r\n");
        }
        return b.toString();
    }
    public static String label(String reference) {
        if(reference==null || reference.isEmpty())return "Missing track";
        String p=reference.substring(reference.lastIndexOf('/')+1);
        int index=p.indexOf('?');if(index>=0)p=p.substring(0,index);
        return p.isEmpty()?"Audio track":p;
    }
    public static List<String> read(String body) {
        List<String> list=new ArrayList<>();
        String normalized=body.replace("\r\n","\n").replace('\r','\n');
        for(String raw:normalized.split("\n")) {
            String line=raw.trim().replace("\uFEFF","");
            if(line.isEmpty()||line.startsWith("#"))continue;
            if(line.startsWith("https:")||line.startsWith("http:"))
                throw new IllegalArgumentException("Remote audio URLs cannot be used in this version.");
            list.add(line);
            if(list.size()>128)throw new IllegalArgumentException("Maximum 128 audio tracks.");
        }
        return list;
    }
}
