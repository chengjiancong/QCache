package client;

import remote.message.Message;

import java.net.Socket;

public interface SocketOutHandler<T extends Message> {
    /**
     * 将msg对象写入 Socket,前两个字节放置消息体的长度
     * @param socket socket.
     * @param msg 需要写入的channel.
     */
    void write(Socket socket, T msg);
}
