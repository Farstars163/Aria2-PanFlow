package com.farstars.service;



import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;


public interface Aria2Client {

    ObjectNode sendRpc(String method, ArrayNode params) throws IOException;

    String addUri(String url, String dir) throws IOException;

    ObjectNode tellStatus(String gid) throws IOException;

    ArrayNode getPeers(String gid) throws IOException;

    ArrayNode tellActive() throws IOException;

    ArrayNode tellWaiting() throws IOException;

    String pause(String gid) throws IOException;

    String unpause(String gid) throws IOException;

    String remove(String gid) throws IOException;

    String removeDownloadResult(String gid) throws IOException;

    ArrayNode tellStopped() throws IOException;

    ObjectNode getGlobalStat()  throws IOException;

    ObjectNode getVersion() throws IOException;

    String addUri(String url, String dir, String filename, List<String> headers) throws IOException;

    /** 添加种子任务(torrent 文件内容 base64) */
    String addTorrent(String torrentBase64, String dir, String filename) throws IOException;

}
