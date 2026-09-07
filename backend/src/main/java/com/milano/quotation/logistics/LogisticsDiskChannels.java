package com.milano.quotation.logistics;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Keep only the current channel in heap while assembling a multi-file import. */
final class LogisticsDiskChannels implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Map<String,Path> files=new LinkedHashMap<>();
    LogisticsDiskChannels(ObjectMapper mapper){this.mapper=mapper;}
    boolean containsKey(String key){return files.containsKey(key);}
    int size(){return files.size();}
    boolean isEmpty(){return files.isEmpty();}
    void put(String key,ObjectNode value)throws IOException {
        var path=files.get(key);
        if(path==null){path=Files.createTempFile("logistics-channel-",".json");files.put(key,path);}
        mapper.writeValue(path.toFile(),value);
    }
    ObjectNode get(String key){
        return (ObjectNode)mapper.readTree(files.get(key).toFile());
    }
    Iterable<ObjectNode> values(){return ()->files.keySet().stream().map(this::get).iterator();}
    public void close()throws IOException {IOException failure=null;for(var path:files.values())try{Files.deleteIfExists(path);}catch(IOException e){failure=e;}if(failure!=null)throw failure;}
}
