package sweetgirl.dataops.runtimecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import tools.jackson.databind.ObjectMapper;

class RuntimeControlFilterTest {
    private static final String TOKEN="a".repeat(64);
    private final ObjectMapper mapper=new ObjectMapper();
    private RuntimeControlFilter filter(){
        var f=new RuntimeControlFilter(()->new RuntimeMeasurement.Measurement("1".repeat(64),"2".repeat(64),"3".repeat(64),
            "4".repeat(64),"v1","5".repeat(64),"6".repeat(64),"7".repeat(64),"8".repeat(64)),mapper,"service","release","generation","nonce",TOKEN);
        f.markReady();return f;
    }
    private MockHttpServletRequest control(String method,String suffix){
        var request=new MockHttpServletRequest(method,"/internal/agent-runtime"+suffix);
        request.setRemoteAddr("127.0.0.1");request.addHeader("X-Agent-Runtime-Token",TOKEN);
        request.addHeader("X-Agent-Expected-Generation","generation");return request;
    }
    @Test void drainWaitsForExistingWorkAndRejectsNewGetLeases() throws Exception {
        var f=filter();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(()->{try {f.doFilter(new MockHttpServletRequest("GET","/sync/lease"),new MockHttpServletResponse(),(req,res)->{
                entered.countDown();try{if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            });}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var draining=new MockHttpServletResponse();f.doFilter(control("POST","/drain"),draining,(req,res)->fail());
            assertEquals(200,draining.getStatus());assertFalse(mapper.readTree(draining.getContentAsString()).path("drained").asBoolean());
            var rejected=new MockHttpServletResponse();f.doFilter(new MockHttpServletRequest("GET","/sync/lease"),rejected,(req,res)->fail());assertEquals(503,rejected.getStatus());
            finish.countDown();running.get(3,TimeUnit.SECONDS);
            var drained=new MockHttpServletResponse();f.doFilter(control("GET",""),drained,(req,res)->fail());
            var state=mapper.readTree(drained.getContentAsString());assertTrue(state.path("drained").asBoolean());assertTrue(state.path("healthy").asBoolean());
            assertEquals(0,state.path("active_requests").asInt());assertFalse(state.path("writes_enabled").asBoolean());
            assertEquals("1".repeat(64),state.path("artifact_sha256").asString());assertFalse(drained.getContentAsString().contains(TOKEN));
        } finally {finish.countDown();pool.shutdownNow();}
    }
    @Test void staleGenerationCannotDrain() throws Exception {
        var f=filter();var request=control("POST","/drain");request.removeHeader("X-Agent-Expected-Generation");request.addHeader("X-Agent-Expected-Generation","old");
        var response=new MockHttpServletResponse();f.doFilter(request,response,(req,res)->fail());assertEquals(409,response.getStatus());
        f.doFilter(new MockHttpServletRequest("GET","/sync/lease"),new MockHttpServletResponse(),(req,res)->{});
    }
    @Test void remoteOrUnauthenticatedControlIsRejectedDespiteForwardedHeaders() throws Exception {
        for(boolean remote:new boolean[]{true,false}) {
            var request=control("GET","");if(remote)request.setRemoteAddr("192.0.2.1");else request.removeHeader("X-Agent-Runtime-Token");
            request.addHeader("X-Forwarded-For","127.0.0.1");var response=new MockHttpServletResponse();
            filter().doFilter(request,response,(req,res)->fail());assertEquals(403,response.getStatus());
        }
    }
    @Test void businessExceptionReleasesDrainOwnership() throws Exception {
        var f=filter();assertThrows(IllegalStateException.class,()->f.doFilter(new MockHttpServletRequest("POST","/snapshot"),new MockHttpServletResponse(),(a,b)->{throw new IllegalStateException();}));
        var response=new MockHttpServletResponse();f.doFilter(control("POST","/drain"),response,(req,res)->fail());
        assertTrue(mapper.readTree(response.getContentAsString()).path("drained").asBoolean());
    }
    @Test void measurementFailureDoesNotFabricateHealthyIdentity() throws Exception {
        var f=new RuntimeControlFilter(()->{throw new IllegalStateException("private detail");},mapper,"s","r","g","n",TOKEN);
        var response=new MockHttpServletResponse();f.doFilter(control("GET",""),response,(req,res)->fail());
        assertEquals(503,response.getStatus());assertFalse(response.getContentAsString().contains("private detail"));
    }
    @Test void tokenRequiresPrivateRegularFile(@TempDir java.nio.file.Path dir) throws Exception {
        var file=dir.toRealPath().resolve("token");Files.writeString(file,TOKEN);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        assertEquals(TOKEN,RuntimeControlFilter.readToken(file));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(IllegalStateException.class,()->RuntimeControlFilter.readToken(file));
        var link=dir.toRealPath().resolve("link");Files.createSymbolicLink(link,file);assertThrows(IllegalStateException.class,()->RuntimeControlFilter.readToken(link));
    }
    @Test void ownershipUsesProcessUidEvenWithoutAnAccountName(@TempDir java.nio.file.Path dir) throws Exception {
        var file=dir.toRealPath().resolve("token");Files.writeString(file,TOKEN);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        String previous=System.getProperty("user.name");
        try {
            System.setProperty("user.name","unmapped-container-uid");
            assertEquals(TOKEN,RuntimeControlFilter.readToken(file));
        } finally {
            if(previous==null)System.clearProperty("user.name");else System.setProperty("user.name",previous);
        }
    }
    @Test void admissionStaysClosedAcrossRestartUntilHostOpensIt(@TempDir java.nio.file.Path dir) throws Exception {
        var path=dir.toRealPath().resolve("admission");
        Files.writeString(path,"CLOSED\n");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        var admission=RuntimeControlFilter.admissionFile(path);
        for(int restart=0;restart<2;restart++) {
            var f=new RuntimeControlFilter(()->new RuntimeMeasurement.Measurement("1".repeat(64),"2".repeat(64),"3".repeat(64),
                "4".repeat(64),"v1","5".repeat(64),"6".repeat(64),"7".repeat(64),"8".repeat(64)),mapper,"service","release","generation","nonce",TOKEN,admission);
            f.markReady();var rejected=new MockHttpServletResponse();
            f.doFilter(new MockHttpServletRequest("GET","/sync/lease"),rejected,(a,b)->fail());assertEquals(503,rejected.getStatus());
            var state=new MockHttpServletResponse();f.doFilter(control("GET",""),state,(a,b)->fail());
            var json=mapper.readTree(state.getContentAsString());assertTrue(json.path("healthy").asBoolean());
            assertTrue(json.path("drained").asBoolean());assertFalse(json.path("admission_open").asBoolean());
            Files.writeString(path,"OPEN\n");
            f.doFilter(new MockHttpServletRequest("GET","/sync/lease"),new MockHttpServletResponse(),(a,b)->{});
            Files.writeString(path,"CLOSED\n");
        }
    }
    @Test void missingMalformedOrPublicAdmissionFailsClosed(@TempDir java.nio.file.Path dir) throws Exception {
        var path=dir.toRealPath().resolve("admission");var admission=RuntimeControlFilter.admissionFile(path);
        assertFalse(admission.get());
        for(String value:new String[]{"OPEN","open\n","OPEN\nextra","CLOSED\n"}) {
            Files.writeString(path,value);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));assertFalse(admission.get());
        }
        Files.writeString(path,"OPEN\n");assertTrue(admission.get());
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));assertFalse(admission.get());
        var link=dir.toRealPath().resolve("link");Files.createSymbolicLink(link,path);assertFalse(RuntimeControlFilter.admissionFile(link).get());
    }
}
