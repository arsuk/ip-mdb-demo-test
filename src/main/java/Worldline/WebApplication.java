package Worldline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public class WebApplication {
	
	final static Logger logger = LogManager.getLogger(WebApplication.class);

    public WebApplication (String host,int port) {
    	
    	logger.info("Starting WebApplication "+host+":"+port);

        Undertow server = Undertow.builder()
                .addHttpListener(port, host, ROUTES)
                .build();
        
        server.start();
    }

    private static HttpHandler ROUTES = new RoutingHandler()
            .post("/stats", RoutingHandlers::stats)
            .post("/tasks", RoutingHandlers::tasks)
            .post("/test", RoutingHandlers::test)
            .get("/about", RoutingHandlers.plainTextHandler("This is the IPtestCommand AP"))
            .setFallbackHandler(RoutingHandlers::htmlHandler);
}

class RoutingHandlers {
	
	static int oldSendCount=0;
	static int oldRecvCount=0;
	
	static Logger logger = LogManager.getRootLogger();
	static SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");	// 15:25:40

    public static HttpHandler plainTextHandler(String value) {

        return new PlainTextHandler(value);
    }

    public static void stats(HttpServerExchange exchange) {

    	exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
    	int recvCount=IPTestCommand.totalRecvCount.get();
    	int sendCount=IPTestCommand.totalSendCount.get();
    	String dataStr=
			"Sessions "+IPTestCommand.sessionCount.get()+" target count "+IPTestCommand.targetMessageCount+" time "+timeFormat.format(new Date())+"\n"+
			"Sent "+(sendCount-oldSendCount)+" total "+sendCount+", TPS "+String.format("%.1f", IPTestCommand.sendTps)+"\n"+
			"Received "+(recvCount-oldRecvCount)+" total "+recvCount+", TPS "+String.format("%.1f", IPTestCommand.recvTps)+"\n";
		if (IPTestCommand.accpCount.get()>0 || IPTestCommand.rjctCount.get()>0)
			dataStr=dataStr+"Accepted "+IPTestCommand.accpCount.get()+ " Rejected "+IPTestCommand.rjctCount+"\n";
		if (IPTestCommand.duplicateCount.get()>0)
			dataStr=dataStr+"Duplicates "+IPTestCommand.duplicateCount.get()+"\n";
		if (IPTestCommand.stopSendFlag) dataStr=dataStr+"Sending stopped\n";
		oldRecvCount=recvCount;
		oldSendCount=sendCount;
    	exchange.getResponseSender().send(dataStr);
        return ;
    }
    
    public static void tasks(HttpServerExchange exchange) {

    	exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
    	String dataStr="Time "+timeFormat.format(new Date())+"\n";
    	String status[]=IPTestCommand.getSessionsStatus();
    	for (int i=0;i<status.length;i++) {
    		dataStr=dataStr+(i+1)+" "+status[i]+"\n";
    	}
    	exchange.getResponseSender().send(dataStr);
        return ;
    }

    public static void test(HttpServerExchange exchange) {
    	
    	Map<String, Deque<String>> params = exchange.getQueryParameters();

    	Deque<String>  tpsStr = params.get("tps");
 		if (tpsStr!=null)
		try {
			int newTPS=Integer.parseInt(tpsStr.getFirst());
			if (IPTestCommand.tps!=newTPS) {
				IPTestCommand.tps=newTPS;
				logger.info("Using TPS parameter "+IPTestCommand.tps);
			}
		} catch (Exception e) {};
		Deque<String>  countStr = params.get("count");
		if (countStr!=null)
		try {
			int newCount=Integer.parseInt(countStr.getFirst());
			if (IPTestCommand.targetMessageCount!=newCount) {
				IPTestCommand.targetMessageCount=newCount;
				logger.info("Using Count parameter "+IPTestCommand.targetMessageCount);
			}
		} catch (Exception e) {};
		Deque<String>  delayStr = params.get("delay");
		if (delayStr!=null)
		try {
			int newDelay=Integer.parseInt(delayStr.getFirst());
			if (IPTestCommand.delay!=newDelay) {
				IPTestCommand.delay=newDelay;
				logger.info("Using Delay parameter "+IPTestCommand.delay);
			}
		} catch (Exception e) {};
		Deque<String>  valueStr = params.get("values");
		if (valueStr!=null)
		try {
			String values=valueStr.getFirst();
			if (values.length()>0) IPTestCommand.values=IPTestCommand.getValueList(values);
		} catch (Exception e) {};
		Deque<String>  taskRestartStr = params.get("taskrestart");
		if (taskRestartStr!=null)
		try {
			int newRestart=Integer.parseInt(taskRestartStr.getFirst());
			if (IPTestCommand.threadRestart!=newRestart) {
				IPTestCommand.threadRestart=newRestart;
				logger.info("Using task restart parameter "+IPTestCommand.threadRestart);
			}
		} catch (Exception e) {};
		Deque<String>  commandStr = params.get("command");
		String cmdStr="";
		if (commandStr!=null)
		try {
			cmdStr=commandStr.getFirst().trim();
			if (cmdStr.isEmpty()) {
				cmdStr="none";
			} else {
				if (cmdStr.equals("stop")) {
					IPTestCommand.stopSendFlag=true;
					IPTestCommand.stopReceiveFlag=true;
				} else if (cmdStr.equals("restart")) {
					if(IPTestCommand.threadRestart>=0) IPTestCommand.threadRestart=IPTestCommand.threadRestart+1;
					IPTestCommand.restart=true;
				} else if (cmdStr.equals("exit")) {
					IPTestCommand.stopSendFlag=true;
					IPTestCommand.stopReceiveFlag=true;
					IPTestCommand.receiveAfterSend=false;
					IPTestCommand.targetMessageCount=0;
				} else {
					cmdStr="unknown "+cmdStr;
				}
				logger.info("Command "+cmdStr);
			}
		} catch (Exception e) {};

		String values="";
		if (IPTestCommand.values!=null)
		for (String value:IPTestCommand.values) {
			if (values.length()>0) values=values+",";
			values=values+value;
		}
    	exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
    	exchange.getResponseSender().send(
    	    			"count       "+IPTestCommand.targetMessageCount+"\n"+
    	    			"TPS         "+IPTestCommand.tps+"\n"+
    	    			"delay       "+IPTestCommand.delay+"\n"+
    	    			"values      "+values+"\n"+
    	    			"taskrestart "+IPTestCommand.threadRestart+"\n\n"+
    	    			"command     "+cmdStr+"\n");

        return;
    }

    public static void htmlHandler(HttpServerExchange exchange) throws IOException {

    	String indexStr="/index.html";
    	String file = exchange.getRequestURI();
    	logger.debug(file);
    	if (file.equals("/")) file=indexStr;	// Otherwise stream will be directory listing
        InputStream stream = PlainTextHandler.class.getResourceAsStream(file);
        if (stream==null) {
        	exchange.setStatusCode(404);
        	exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        	exchange.getResponseSender().send("Page Not Found "+file+"\n");
        } else {
        		exchange.getResponseSender().send(ByteBuffer.wrap(stream.readAllBytes()));
        }
    }
}

class PlainTextHandler implements HttpHandler {
	
	Logger logger = LogManager.getRootLogger();

    private final String value;

    public PlainTextHandler(String value) {
        this.value = value;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
 
    	logger.debug(value);	
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send(value + "\n");
    }
}
