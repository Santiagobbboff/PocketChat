package dev.pocketchat;
import java.io.*;
import java.net.*;
import java.security.*;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;

/** Verifies the bundled root loader and rejection of an untrusted TLS server. */
public final class TlsTest {
    public static void main(String[] args)throws Exception {
        final String bundle=args[0];
        Api.setRootSource(new Api.RootSource(){public InputStream open()throws IOException{return new FileInputStream(bundle);}});
        java.lang.reflect.Method factory=Api.class.getDeclaredMethod("secureFactory");factory.setAccessible(true);
        if(!(factory.invoke(null) instanceof SSLSocketFactory))throw new AssertionError("CA loader failed");
        KeyStore ks=KeyStore.getInstance("JKS");
        try(InputStream in=new FileInputStream(args[1])){ks.load(in,"android".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,"android".toCharArray());
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/",ex->{byte[] b="{}".getBytes("UTF-8");ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});
        server.start();
        try{
            Api.Config c=new Api.Config(1,"https://127.0.0.1:"+server.getAddress().getPort(),"","test");
            try{new Api().call(c,"/",null);throw new AssertionError("Untrusted TLS server was accepted");}
            catch(SSLHandshakeException expected){System.out.println("PASS: CA bundle loads; untrusted TLS certificate is rejected");}
        }finally{server.stop(0);}
    }
}
