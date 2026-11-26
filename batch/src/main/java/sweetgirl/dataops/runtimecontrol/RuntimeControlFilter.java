package sweetgirl.dataops.runtimecontrol;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import tools.jackson.databind.ObjectMapper;

/** Optional loopback control plane. Draining rejects all new business requests, including GET leases. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name="dataops.agent.runtime.enabled",havingValue="true")
public class RuntimeControlFilter implements Filter {
    private static final String PATH="/internal/agent-runtime";
    private static final UserPrincipal PROCESS_OWNER=processOwner();
    private final Object monitor=new Object();
    private final Supplier<RuntimeMeasurement.Measurement> measurement;
    private final ObjectMapper mapper;
    private final String serviceId,releaseId,generation,nonce,token;
    private final Supplier<Boolean> admission;
    private int active;
    private boolean draining;
    private volatile boolean ready;

    @Autowired
    public RuntimeControlFilter(RuntimeMeasurement measurement,ObjectMapper mapper,Environment environment) throws IOException {
        this(measurement::measure,mapper,required(environment,"service-id"),required(environment,"release-id"),
            required(environment,"generation"),required(environment,"launch-nonce"),readToken(Path.of(required(environment,"token-file"))),
            admissionFile(Path.of(required(environment,"admission-file"))));
        if(environment.getProperty("dataops.serving.write-enabled",Boolean.class,false)
            || environment.getProperty("dataops.auto-apply.enabled",Boolean.class,false)
            || environment.getProperty("dataops.agent.apply.enabled",Boolean.class,false)
            || environment.getProperty("dataops.agent.replay.enabled",Boolean.class,false)
            || !"PROPOSAL_ONLY".equals(environment.getProperty("dataops.reconcile.mode")))
            throw new IllegalStateException("MANAGED_RUNTIME_MUST_BE_PROPOSAL_ONLY");
    }

    RuntimeControlFilter(Supplier<RuntimeMeasurement.Measurement> measurement,ObjectMapper mapper,String serviceId,
            String releaseId,String generation,String nonce,String token) {
        this(measurement,mapper,serviceId,releaseId,generation,nonce,token,()->true);
    }

    RuntimeControlFilter(Supplier<RuntimeMeasurement.Measurement> measurement,ObjectMapper mapper,String serviceId,
            String releaseId,String generation,String nonce,String token,Supplier<Boolean> admission) {
        for(String id: new String[]{serviceId,releaseId,generation,nonce})
            if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}")) throw new IllegalArgumentException("RUNTIME_ID_INVALID");
        if(token==null || !token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("RUNTIME_TOKEN_INVALID");
        this.measurement=measurement;this.mapper=mapper;this.serviceId=serviceId;this.releaseId=releaseId;
        this.generation=generation;this.nonce=nonce;this.token=token;this.admission=admission;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markReady(){ready=true;}

    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
        String path=request.getRequestURI();
        if(path.equals(PATH) || path.equals(PATH+"/drain")) {
            control(request,response,path);return;
        }
        if(path.equals("/actuator/health")) {chain.doFilter(input,output);return;}
        synchronized(monitor) {
            if(draining || !admissionOpen()){json(response,503,Map.of("code","SERVICE_DRAINING"));return;}
            active++;
        }
        var settled=new AtomicBoolean();
        Runnable finish=()->{if(settled.compareAndSet(false,true))synchronized(monitor){active--;monitor.notifyAll();}};
        try {chain.doFilter(input,output);}
        finally {
            if(request.isAsyncStarted()) {
                try {request.getAsyncContext().addListener(new AsyncListener(){
                    public void onComplete(AsyncEvent event){finish.run();}
                    public void onTimeout(AsyncEvent event){/* Completion, not timeout notification, ends ownership. */}
                    public void onError(AsyncEvent event){/* Container must still complete the request. */}
                    public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}
                });}catch(IllegalStateException alreadyCompleted){finish.run();}
            } else finish.run();
        }
    }

    private void control(HttpServletRequest request,HttpServletResponse response,String path) throws IOException {
        String supplied=request.getHeader("X-Agent-Runtime-Token");
        if(!loopback(request.getRemoteAddr()) || supplied==null
            || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.UTF_8))) {
            json(response,403,Map.of("code","RUNTIME_CONTROL_FORBIDDEN"));return;
        }
        if(path.endsWith("/drain")) {
            if(!"POST".equals(request.getMethod())){json(response,405,Map.of("code","METHOD_REQUIRED"));return;}
            if(!generation.equals(request.getHeader("X-Agent-Expected-Generation"))) {
                json(response,409,Map.of("code","RUNTIME_GENERATION_CHANGED"));return;
            }
            synchronized(monitor){draining=true;}
        } else if(!"GET".equals(request.getMethod())){json(response,405,Map.of("code","METHOD_REQUIRED"));return;}
        try {
            var measured=measurement.get();var result=mapper.createObjectNode();
            result.put("service_id",serviceId).put("release_id",releaseId).put("generation",generation).put("launch_nonce",nonce)
                .put("artifact_sha256",measured.artifactSha256()).put("parser_digest",measured.parserDigest())
                .put("profile_digest",measured.profileDigest()).put("contract_digest",measured.contractDigest())
                .put("contract_version",measured.contractVersion()).put("policy_digest",measured.policyDigest())
                .put("database_identity",measured.databaseIdentity()).put("dataops_database_identity",measured.dataopsDatabaseIdentity())
                .put("serving_database_identity",measured.servingDatabaseIdentity()).put("writes_enabled",false)
                .put("healthy",ready).put("pid",ProcessHandle.current().pid());
            synchronized(monitor){
                boolean open=admissionOpen(),closed=draining||!open;
                result.put("admission_open",open).put("draining",closed).put("drained",closed&&active==0).put("active_requests",active);
            }
            json(response,200,result);
        }catch(RuntimeException unavailable){json(response,503,Map.of("code","RUNTIME_MEASUREMENT_UNAVAILABLE"));}
    }

    private void json(HttpServletResponse response,int code,Object value) throws IOException {
        response.setStatus(code);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");response.getWriter().write(mapper.writeValueAsString(value));
    }
    private static boolean loopback(String address) {
        // No hostnames, forwarded headers or DNS resolution in the authentication decision.
        if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(address))return false;
        try{return InetAddress.getByName(address).isLoopbackAddress();}catch(IOException failure){return false;}
    }
    private static String required(Environment environment,String key){return environment.getRequiredProperty("dataops.agent.runtime."+key);}
    private boolean admissionOpen() {
        try {return Boolean.TRUE.equals(admission.get());}
        catch(RuntimeException unavailable){return false;}
    }
    /** An admission file survives JVM/launchd restart. Missing or unsafe evidence stays closed. */
    static Supplier<Boolean> admissionFile(Path path) {
        if(!path.isAbsolute())throw new IllegalArgumentException("ABSOLUTE_ADMISSION_FILE_REQUIRED");
        return ()->{
            try {
                privateFile(path);
                return Files.size(path)==5 && "OPEN\n".equals(Files.readString(path));
            }catch(IOException|RuntimeException unavailable){return false;}
        };
    }
    private static void privateFile(Path path) throws IOException {
        if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)
            || !Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
            || !Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(PROCESS_OWNER))
            throw new IllegalStateException("PRIVATE_RUNTIME_FILE_REQUIRED");
    }
    /** The kernel assigns ownership even when a container UID has no passwd entry. */
    private static UserPrincipal processOwner() {
        try {
            Path identity=Files.createTempFile("dataops-runtime-owner-",".tmp");
            try {return Files.getOwner(identity,LinkOption.NOFOLLOW_LINKS);}
            finally {Files.delete(identity);}
        } catch(IOException unavailable) {
            throw new IllegalStateException("PROCESS_FILE_OWNER_UNAVAILABLE",unavailable);
        }
    }
    static String readToken(Path path) throws IOException {
        privateFile(path);
        return Files.readString(path).strip();
    }
}
