package com.pressurediary.app;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

public final class SyncProtocolTests {
    private static int checks;
    private static final String INSTALLATION = "8b6ca901-bb71-46f6-8103-bd097bbf2f44";
    private static final String JOB = "5645cf2c-ec41-439f-a9f9-7156d26d170a";
    private static final String TOKEN = "host-only-test-key-123456789012345678901";

    private static Measurement row(long id, String source) {
        return new Measurement(id,128,76,72,1791591300123L,"早餐后\n休息五分钟",source,"Asia/Shanghai",480);
    }

    public static void main(String[] args) throws Exception {
        List<Measurement> rows = new ArrayList<>();
        for (int i=1;i<=26;i++) rows.add(row(i, i==1 ? "legacy" : i==2 ? "import" : "manual"));
        MeasurementSnapshot snapshot = new MeasurementSnapshot(12,26,rows);
        rows.clear();
        require(snapshot.records.size()==26,"snapshot does not share mutable input");
        rejects(() -> new MeasurementSnapshot(1,2,Arrays.asList(row(2,"manual"),row(1,"manual"))),"order rejected");
        rejects(() -> new MeasurementSnapshot(1,2,Arrays.asList(row(1,"manual"),row(1,"manual"))),"duplicate rejected");
        rejects(() -> new MeasurementSnapshot(1,0,Arrays.asList(row(1,"manual"))),"watermark rejected");
        rejects(() -> new MeasurementSnapshot(0,0,Collections.emptyList()),"zero revision rejected");
        require(MeasurementUploadProtocol.sha256(new byte[0]).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"empty digest");
        Harness success = new Harness();
        success.protocol().sync("https://bp.example.com",TOKEN,INSTALLATION,snapshot,()->{});
        require(success.calls.size()==4,"start + two pages + commit");
        StringBuilder hashes = new StringBuilder();
        for(int i=1;i<=2;i++) {
            Fake call=success.calls.get(i);
            JSONObject body=new JSONObject(call.body.toString("UTF-8"));
            JSONArray page=body.getJSONArray("records");
            require(page.length()==(i==1?25:1),"page size");
            require(page.getJSONObject(0).getLong("localId")== (i==1?1:26),"old to new order");
            require(page.getJSONObject(0).getInt("schemaVersion")==2,"version2 wire");
            require(page.getJSONObject(0).getInt("utcOffsetMinutes")==480,"stored timezone");
            hashes.append(MeasurementUploadProtocol.sha256(call.body.toByteArray())).append('\n');
        }
        require(new JSONObject(success.calls.get(1).body.toString("UTF-8")).getJSONArray("records")
                .getJSONObject(1).getString("source").equals("import"),"import metadata sent");
        require(success.start.getString("digest").equals(MeasurementUploadProtocol.sha256(hashes.toString().getBytes("US-ASCII"))),"hash matches exact page bytes");
        for(Fake call:success.calls) {
            require(call.disconnected,"connection closed");
            require(!call.getInstanceFollowRedirects(),"no redirect forwarding");
            require(call.getRequestProperty("Authorization").equals("Bearer "+TOKEN),"upload authorization");
        }
        for(String fault:new String[]{"page","commit","revision","digest","count","network"}) {
            Harness broken=new Harness();broken.fault=fault;
            fails(()->broken.protocol().sync("https://bp.example.com",TOKEN,INSTALLATION,snapshot,()->{}),"failed sync not acknowledged: "+fault);
            if(fault.equals("page")) require(broken.calls.size()==2,"bad page stops before commit");
        }
        Harness cancelled=new Harness();
        final int[] checksAt={0};
        fails(()->cancelled.protocol().sync("https://bp.example.com",TOKEN,INSTALLATION,snapshot,()->{
            if(++checksAt[0]==3) throw new IOException("settings changed");
        }),"cancelled scope rejected");
        require(cancelled.calls.size()==2,"cancel before more pages/commit");
        Harness lost=new Harness();lost.alreadyCommitted=true;
        lost.protocol().sync("https://bp.example.com",TOKEN,INSTALLATION,snapshot,()->{});
        require(lost.calls.size()==1,"lost ack recovered without repeating pages");
        Harness empty=new Harness();
        empty.protocol().sync("https://bp.example.com",TOKEN,INSTALLATION,new MeasurementSnapshot(13,26,Collections.emptyList()),()->{});
        require(empty.calls.size()==2,"delete-all has no page but must commit");
        if(args.length==1) integration(args[0],System.getenv("BP_TEST_TOKEN"));
        System.out.println("PASS: "+checks+" assertions (atomic snapshot protocol)");
    }

    private static void integration(String base,String token) throws Exception {
        MeasurementUploadProtocol p=new MeasurementUploadProtocol();
        List<Measurement> rows=new ArrayList<>();
        for(int i=1;i<=31;i++) rows.add(row(i,i==7?"legacy":"manual"));
        p.sync(base,token,INSTALLATION,new MeasurementSnapshot(1,31,rows),()->{});
        rows=Arrays.asList(new Measurement(1,144,78,70,1791591300123L,"修改", "manual","Asia/Shanghai",480),row(7,"legacy"),row(32,"import"));
        MeasurementSnapshot next=new MeasurementSnapshot(2,32,rows);
        p.sync(base,token,INSTALLATION,next,()->{});
        p.sync(base,token,INSTALLATION,next,()->{});
        p.sync(base,token,INSTALLATION,new MeasurementSnapshot(3,32,Collections.emptyList()),()->{});
        p.sync(base,token,INSTALLATION,new MeasurementSnapshot(4,34,Arrays.asList(row(33,"import"),row(34,"camera"))),()->{});
        require(true,"real HTTPS backfill/edit/delete/import/empty/retry completed");
    }

    static final class Harness {
        final List<Fake> calls=new ArrayList<>(); JSONObject start; String fault=""; boolean alreadyCommitted;
        MeasurementUploadProtocol protocol(){ return new MeasurementUploadProtocol(url->{Fake call=new Fake(url,this);calls.add(call);return call;}); }
        String reply(Fake call) throws IOException {
            try {
                String path=call.getURL().getPath();
                if(path.endsWith("/snapshots")) {
                    start=new JSONObject(call.body.toString("UTF-8"));
                    JSONObject response=new JSONObject().put("committed",alreadyCommitted).put("syncId",JOB)
                            .put("version",fault.equals("revision")?999:start.getLong("version"))
                            .put("digest",fault.equals("digest")?"wrong":start.getString("digest"))
                            .put("recordCount",start.getInt("recordCount"));
                    return response.toString();
                }
                if(path.contains("/pages/")) return new JSONObject().put("staged",!fault.equals("page"))
                        .put("syncId",JOB).put("pageIndex",Integer.parseInt(path.substring(path.lastIndexOf('/')+1))).toString();
                return new JSONObject().put("committed",!fault.equals("commit")).put("version",start.getLong("version"))
                        .put("digest",start.getString("digest")).put("recordCount",fault.equals("count")?999:start.getInt("recordCount")).toString();
            } catch(Exception error){throw new IOException(error);}
        }
    }
    static final class Fake extends HttpsURLConnection {
        final ByteArrayOutputStream body=new ByteArrayOutputStream();final Harness owner;boolean disconnected;
        Fake(URL url,Harness owner){super(url);this.owner=owner;}
        public void connect(){} public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "TEST";} public Certificate[] getLocalCertificates(){return null;}
        public Certificate[] getServerCertificates(){return new Certificate[0];}
        public OutputStream getOutputStream(){return body;}
        public String getContentType(){return "application/json";}
        public int getResponseCode() throws IOException {if(owner.fault.equals("network"))throw new IOException("interrupted");return 200;}
        public InputStream getInputStream() throws IOException {return new ByteArrayInputStream(owner.reply(this).getBytes("UTF-8"));}
    }
    interface Action{void run()throws Exception;}
    static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static void rejects(Action a,String m)throws Exception{boolean rejected=false;try{a.run();}catch(IllegalArgumentException e){rejected=true;}require(rejected,m);}
    static void fails(Action a,String m)throws Exception{boolean failed=false;try{a.run();}catch(IOException|org.json.JSONException e){failed=true;}require(failed,m);}
}
