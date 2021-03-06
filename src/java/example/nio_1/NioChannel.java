package nio_1;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;

/**
 * 连接对象
 */
public class NioChannel {
    private static Logger logger = LoggerFactory.getLogger(NioChannel.class);
    private Selector selector;
    /**
     * 每个channel 初始分配1024个byte read缓冲区,不够再分配更大的
     */
    private int readBufSize = 1024;
    private ByteBuffer readBuffer = ByteBuffer.allocate(readBufSize);


    /**
     * 每个channel 初始分配1024个byte write缓冲区,不够再分配更大的
     */
    private int writeBufSize = 1024;
    private ByteBuffer writeBuf = ByteBuffer.allocateDirect(writeBufSize);

    /**
     * 消息体长度
     */
    private short bodyLen = -1;

    /**
     * 包长度信息占两个字节
     */
    private ByteBuffer head = ByteBuffer.allocateDirect(2);


    /**
     * selector key 可唯一标识一个Channel
     */
    private SelectionKey selectionKey;
    /**
     * java nio SocketChannel
     */
    private SocketChannel channel;
    /**
     * 管理该Channel的 ChannelGroup
     */
    private NioChannelGroup nioChannelGroup;
    /**
     * ProtoBuf 反序列化需要提供的
     */
    private MessageLite messageLite;


    public NioChannel(NioChannelGroup nioChannelGroup,
                      SelectionKey selectionKey,
                      MessageLite messageLite) {
        if (selectionKey == null) {
            throw new IllegalArgumentException("selectionKey can not be null");
        }
        if (!selectionKey.isValid()) {
            throw new IllegalArgumentException("invalid selectionKey");
        }

        if (messageLite == null) {
            throw new IllegalArgumentException("messageLite can not be null");
        }
        this.messageLite = messageLite;
        this.nioChannelGroup = nioChannelGroup;
        this.selectionKey = selectionKey;
        this.selector = selectionKey.selector();
        this.channel = (SocketChannel) selectionKey.channel();
    }

    /**
     * 将连接注册到另外一个Selector.
     * @param selector selector
     * @param interestOps interestOps
     * @return SelectionKey
     */
    public SelectionKey register(Selector selector,int interestOps) {
        this.selector = selector;
        try {
            return channel.register(selector,interestOps);
        } catch (ClosedChannelException e) {
            logger.error("can not register {}",e);
        }
        return null;
    }

    /**
     * 返回channel.
     * @return channel
     */
    public SelectableChannel channel(){
        return this.channel;
    }

    /**
     * 获取该channel的selector.
     * @return selector
     */
    public Selector selector(){
        return selector;
    }

    /**
     * 关闭该channel
     */
    public void close(){
        try {
            nioChannelGroup.remove(this);
            channel.close();
            selectionKey.cancel();
        } catch (IOException e) {
            logger.error("close channel error {}",e);
        }
    }

    /**
     * 写数据.
     * @param messageLite msg
     */
    public void write(MessageLite messageLite){
        int bodySize = messageLite.getSerializedSize();
        if (bodySize > Short.MAX_VALUE) {
            throw new RuntimeException("msg length must not bigger than  " + Short.MAX_VALUE);
        }

        if (bodySize + 2 > writeBufSize) {
            writeBufSize = bodySize + 2;
            writeBuf = ByteBuffer.allocateDirect(writeBufSize + 2);
        }
        writeBuf.clear();
        writeBuf.putShort((short)bodySize);
        writeBuf.put(messageLite.toByteArray());
        writeBuf.flip();
        try {
            channel.write(writeBuf);
        } catch (IOException e) {
            this.close();
            logger.error("write error: {}",e);
        }

    }


    /**
     * 读数据
     */
    public Object read() {
        //读取头信息
        if (bodyLen == -1) {
            try {
                int size = channel.read(head);
                if (size == -1) {
                    close();
                }
                if (head.position() == 2) {
                    //读满了两个字节
                    head.flip();
                    bodyLen = head.getShort();
                    head.clear();
                }
            } catch (IOException e) {
                logger.error("read error: {} ",e);
            }
        }
        if (bodyLen > 0 ){
            try {
                if (bodyLen > readBufSize) {
                    //重新分配cache
                    readBufSize = bodyLen;
                    readBuffer = ByteBuffer.allocate(bodyLen);
                }
                readBuffer.limit(bodyLen);
                int size = channel.read(readBuffer);
                if (size == -1) {
                    close();
                }
            } catch (IOException e) {
                logger.error(e.toString());
            }
            if (completePackage()) {
                //读完了一个完整的数据
                try {
                    readBuffer.flip();
                    MessageLite msg = messageLite.getDefaultInstanceForType()
                            .newBuilderForType()
                            .mergeFrom(readBuffer.array(), 0,bodyLen)
                            .build();

                    readBuffer.clear();
                    bodyLen = -1;
                    return msg;
                } catch (InvalidProtocolBufferException e) {
                    logger.error(e.toString());
                }
            }
        }
        return null;
    }

    /**
     * 是否有一个完整包数据
     * @return bool
     */
    public boolean  completePackage() {
        return bodyLen == readSize();
    }

    /**
     * 读取到的body len
     * @return 已经读到的body size
     */
    private int readSize(){
        return readBuffer.position();
    }


    /**
     *  获取消息体的长度,若为 -1 说明没有读取到包的长度信息,继续读取
     * @return int
     */
    private int bodyLen(){
        return bodyLen;
    }

    /**
     * 获取selectorKey.
     * @return selectionKey.
     */
    public SelectionKey selectionKey(){
        return this.selectionKey;
    }

    public void interestOps(int interestOps){
        selectionKey.interestOps(interestOps);
    }
    @Override
    public int hashCode() {
        return this.selectionKey.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (! (obj instanceof NioChannel)) {
            return false;
        }
        return this.selector.equals(((NioChannel)obj).selector());
    }
}
