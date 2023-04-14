package Worldline;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.artemis.api.core.client.FailoverEventListener;
import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.transport.TransportListener;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.ExceptionListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IPTestCommand<br/>
 * This is a java command line application that initiates ActiveMQ messages for testing Instant Payments. It creates pacs.008 messages.
 * The main method structure is:
 *  The main method creates the ActiveMQ factory using parameters and JNDI properties,
 *  The main method loops until the target test message send count is reached and starts/restarts session tasks
 *  The main method loops while the open session tasks are still receiving.
 * The session tasks are created or restarted by the main method:
 *  The sessions create a connection and message listener if a response queue is defined
 *  The sessions loops sending messages to the request queue until the target count is reached. It sleeps for a calculated time so
 *  that the request TPS rate is maintained.
 *  The Sessions loop waiting for responses and exits if a restart is requested or if no responses are received.
 *    
 * @author Allan Smith
 * 
 */
public class IPTestCommand implements MessageListener,ExceptionListener,TransportListener,FailoverEventListener,Runnable {
	
	private static final Logger logger = LogManager.getLogger(IPTestCommand.class);
	private static Level VERBOSE = Level.forName("VERBOSE", 550);

    static final long HUNDREDMILLIS=100;
    static final long ONESEC=1000;
    static final long TENSECS=10000;
    static final int DEFAULTCONNECTIONS=8;

	private static final String destinationJndiName = "instantpayments_mybank_originator_request";
	private static final String responseJndiName = "instantpayments_mybank_originator_response";

	static String defaultTemplate="pacs.008.xml"; 

	static boolean stopSendFlag=false;
	static boolean stopReceiveFlag=false;
	static boolean restart;
	
	private static Context ic=null;
	private static ConnectionFactory cf;
	private static byte[] docText;
	static String[] values;
	static boolean receiveAfterSend;
	private static String creditorBICs[];
	private static  String debtorBICs[];
	private static String creditorIBANs[];
	private static String debtorIBANs[];
	private static String stopStatus;
	private static String stopReason;
	private static boolean duplicateCheck;

	static 	Random randomNumbers=new Random();
	static AtomicInteger totalSendCount=new AtomicInteger(0);
	static AtomicLong totalSendTime=new AtomicLong(0);
	static AtomicInteger totalRecvCount=new AtomicInteger(0);
	static AtomicInteger sessionCount=new AtomicInteger(0);	// Number of tasks created for running test send / receive sessions 
	static AtomicInteger curConnectedCount=new AtomicInteger(0); // Number of session connections open (used for TPS timing calculation)
	static AtomicInteger stopCount=new AtomicInteger(0);
	static AtomicInteger accpCount=new AtomicInteger(0);
	static AtomicInteger rjctCount=new AtomicInteger(0);
	static AtomicInteger duplicateCount=new AtomicInteger(0);
	LinkedList<String> txList = new LinkedList<String>();

	static float recvTps=0;
	static float sendTps=0;
	static int threadRestart=0;	// Task restart on fail - 0 no restarts, >0 number of restarts, <0 always restart
	static int range;
	static int trace;
	static boolean randomFlag;
	static int tps;
	static int targetMessageCount; // Message count - 0 no messages, >0 number of messages, < 0 no messages and only wait for responses.
	static int connectionCount=DEFAULTCONNECTIONS;	// Max number of test sessions, can be updated 
	static int stopCountMax;
	static int delay=0;
	static String[] threadStatus=null;
	static Hashtable<Long,Integer> threadMap=new Hashtable<Long,Integer>();
	static Thread threads[]=null;
	static int threadRestarts[]=null;
	private static SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");	// 15:25:40
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");	// 2018-12-28
	private static SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264
	private static SimpleDateFormat dateTimeFormatGMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264

    int mySendCount=0;	// Class run thread count for logging
    int myRecvCount=0;	// Class listener thread count for logging
    int threadIndex;	// Index matching runnable class task thread to message send/receive session
    
	public static void main (String args[]) {

		logger.log(VERBOSE,"Class path: "+System.getProperty("java.class.path"));

		String templateFile=null;
		String creditorBICsStr=null;
		String debtorBICsStr=null;
		String jndiProperties=null;
		String valueStr=null;
		String creditorIBANsStr=null;
		String debtorIBANsStr=null;
		
		try {
			CliArgs cliArgs=new CliArgs(args);
			Arguments myArgs=cliArgs.switchPojo(Arguments.class);

	        if (myArgs._h || myArgs._help) {
                String version = IPTestCommand.class.getPackage().getImplementationVersion();
	            System.out.println("IPTestCommand "+version);
	            System.out.println("Usage: <count> <tps> -template templatefile -values amounts -creditor-bics bics -debtor-bics bics -properties jndiprops");
	            System.out.println("    -tps-range n -creditor-ibans ibans -debtor-ibans ibans -stop-status status -stop-count n -duplicate-check");
                System.out.println("    -receive-after-send");
	            System.out.println("Defaults are: count=10 tps=1 template=pacs.008.xml");
	            System.out.println("Change the jndi.properties file to change the broker hostname, queue names and other techinal items.");
	            System.out.println("'-h' or '-help' gives this help info.");
	            System.out.println("The values and bics/ibans parameters are used to replace the template elements for testing purposes.");
	            System.out.println("These parameters can be comma separated lists, elements are selected randomly. If a filename is given");
	            System.out.println("instead of a list then the file should have one element one per line.");          
	            System.out.println("The tpsrange argument can be used to vary the tps rate within a second for + or - of the given range.");
	            System.out.println("If count is zero then the tool will not send messages and will receive messages until the queue is empty (-1 forever).");
	            System.out.println("Stop status is used to check the response status and stop the test if it matches after a stop count (default 1).");
	            System.out.println("The stop status is a comma seperated string for status and reason, e.g. 'ACCP' or 'RJCT,AB05', regex syntax supported.");
	            System.out.println("-duplicate-check will display a warning if the last 10000 responses include a duplicate TXID for the current message.");
	            System.out.println("If -receive-after-send is present and there is a response queue then the command will wait for responses indefinitely.");
	            System.out.println("Some parameters can be modified at runtime by editing the jndi properties file or by using the web interface.");
	            System.out.println("See the sample properties files for examples.");
	            System.exit(0);
	        }
	        targetMessageCount = myArgs.count;
			tps= myArgs.tps;
			trace = myArgs._trace;
			templateFile = myArgs._template;
			creditorBICsStr=myArgs._creditor_bics;
			debtorBICsStr=myArgs._debtor_bics;
			jndiProperties=myArgs._properties;
			valueStr = myArgs._values;
			range = myArgs._tps_range;
			creditorIBANsStr = myArgs._creditor_ibans;
			debtorIBANsStr = myArgs._debtor_ibans;
			stopCountMax=myArgs._stop_count;
			receiveAfterSend = myArgs._receive_after_send;
			stopStatus=myArgs._stop_status;
			duplicateCheck=myArgs._duplicate_check;
			
		} catch (RuntimeException e) {
			String errorText="Argument error: ";
			if (e.getCause()==null)
				System.out.println(errorText+e);
			else
				System.out.println(errorText+e.getCause());
			//e.printStackTrace();
			System.exit(1);
		}

		if (stopStatus!=null) {
			String s[]=stopStatus.split(",");
			if (s.length>1) {
				stopStatus=s[0];
				stopReason=s[1];
			} else
				stopReason=null;
		}

		values=getValueList(valueStr);
		creditorBICs=getValueList(creditorBICsStr);
		debtorBICs=getValueList(debtorBICsStr);
		creditorIBANs=getValueList(creditorIBANsStr);
		debtorIBANs=getValueList(debtorIBANsStr);
		
		// Handle interrupts and stop gracefully if possible if we want to cut short the test
		Runtime.getRuntime().addShutdownHook(new Thread() {
	        public void run() {
	        	//logger.info("Interrupted");
            	stopSendFlag=true;
            	stopReceiveFlag=true;
            	receiveAfterSend=false;
            	try {Thread.sleep(ONESEC*2);}
            	catch (Exception e) {logger.info("Bad interrupt");}
	        }
	    });

		docText=XMLutils.getTemplate(templateFile);
		if (docText==null) {
			logger.error("Missing document template "+templateFile);
			System.exit(1);
		}

		Document msgDoc = XMLutils.bytesToDoc(docText);
		if (msgDoc==null) {
			logger.error("Parsing document template "+templateFile);
			System.exit(1);
		}
		
		if (valueStr==null) valueStr=XMLutils.getElementValue(msgDoc,"IntrBkSttlmAmt");
		
		try {
			Properties props=new Properties ();
			File propsFile=null;
               try {
               	propsFile=new File(jndiProperties);
    			props.load(new FileInputStream(propsFile));
                   // Can throw null pointer if factory property or classes bad
			    ic = new InitialContext(props);
               } catch (Exception ice) {
                   logger.error("Initial context properties "+ice);
                   System.exit(1);
               }                
			String connectionCountStr = props.getProperty("connectionCount");
			if (connectionCountStr!=null)
			try {
				connectionCount=Integer.parseInt(connectionCountStr);
			} catch (Exception e) {logger.warn("Bad connectionCount property");};
							
			String resp="IPTestCommand: "+templateFile+" Value: "+valueStr+" Count: "+targetMessageCount+" TPS: "+tps+" +/- "+range;
			logger.info(resp+"\n");

            String connectionFactoryStr="ConnectionFactory";

            logger.info("Getting Factory from jndi "+jndiProperties+" "+connectionFactoryStr);

            cf=(QueueConnectionFactory)ic.lookup(connectionFactoryStr);

			try {
				org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory acf=(org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory)cf;
				logger.log(VERBOSE,"Class "+acf.getClass());
				logger.log(VERBOSE,"User "+acf.getUser());
				logger.log(VERBOSE,"Password "+acf.getPassword());
				logger.log(VERBOSE,"CallFailoverTimeout "+acf.getCallFailoverTimeout());
				logger.log(VERBOSE,"CallTimeout "+acf.getCallTimeout());
				logger.log(VERBOSE,"ReconnectAttempts "+acf.getReconnectAttempts());
				logger.log(VERBOSE,"MaxRetryInterval "+acf.getMaxRetryInterval());
				logger.log(VERBOSE,"RetryInterval "+acf.getRetryInterval());
				logger.log(VERBOSE,"InitialConnectionAttempts "+acf.getInitialConnectAttempts());
				logger.log(VERBOSE,"BlockOnDurableSend "+acf.isBlockOnDurableSend());
				logger.log(VERBOSE,"BlockOnNonDurableSend "+acf.isBlockOnNonDurableSend());
				logger.log(VERBOSE,"BlockOnAcknowledge "+acf.isBlockOnAcknowledge());
				logger.log(VERBOSE,"EnableSharedClientID "+acf.isEnableSharedClientID());
				logger.log(VERBOSE,"HA "+acf.isHA());
				logger.log(VERBOSE,"FailoverOnInitialConnection "+acf.isFailoverOnInitialConnection());
				logger.log(VERBOSE,"UseTopologyForLoadBalancing "+acf.isUseTopologyForLoadBalancing());
				String shortNameStr=acf.getConnectionLoadBalancingPolicyClassName();
				int i=shortNameStr.lastIndexOf(".");
				if (shortNameStr!=null&&i>0) shortNameStr=shortNameStr.substring(i + 1);
				logger.log(VERBOSE,"ConnectionLoadBalancingPolicyClassName "+shortNameStr);
				logger.log(VERBOSE,"ConsumerMaxRate "+acf.getConsumerMaxRate());					
				logger.log(VERBOSE,"ProducerrMaxRate "+acf.getProducerMaxRate());
				logger.log(VERBOSE,"ConnectionTTL "+acf.getConnectionTTL());
				logger.log(VERBOSE,"ClientConnectionCheckPeriod "+acf.getClientFailureCheckPeriod());
				logger.log(VERBOSE,"ClientID "+acf.getClientID());
			} catch (Exception e) {};
			try {
				org.apache.activemq.ActiveMQConnectionFactory acf=(org.apache.activemq.ActiveMQConnectionFactory)cf;
				logger.log(VERBOSE,"Class "+acf.getClass());
				logger.log(VERBOSE,"UserName "+acf.getUserName());
				logger.log(VERBOSE,"Password "+acf.getPassword());
				logger.log(VERBOSE,"ConnectResponseTimeout "+acf.getConnectResponseTimeout());
				logger.log(VERBOSE,"SendTimeout "+acf.getSendTimeout());
				logger.log(VERBOSE,"SendAckAsync "+acf.isSendAcksAsync());
				logger.log(VERBOSE,"StatsRnabled "+acf.isStatsEnabled());
				logger.log(VERBOSE,"BrokerURL "+acf.getBrokerURL());
				logger.log(VERBOSE,"ClientID "+acf.getClientID());				
			} catch (Exception e) {};
				
			logger.info("Starting "+connectionCount+" sessions");
				
			System.out.println("Log level trace "+logger.isTraceEnabled()+", debug "+logger.isDebugEnabled()+", verbose "+(logger.getLevel()==VERBOSE)+", info "+logger.isInfoEnabled()
				+", warn "+logger.isWarnEnabled()+", error "+logger.isErrorEnabled());
				
			threads = new Thread[connectionCount];
			threadRestarts = new int [connectionCount];
			threadStatus = new String [connectionCount];

			String webHost=props.getProperty("webhost");
			if (webHost!=null) {
				int port=8080;
				String hostPort[]=webHost.split(":");
				if (hostPort.length>1) try {
					webHost=hostPort[0];
					port=Integer.parseInt(hostPort[1]);
				} catch (Exception e) {logger.info("Bad port value");};
				new WebApplication(webHost,port);
			}
		} catch (javax.naming.NameNotFoundException e) {
			logger.error("Context lookup exception: "+e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
			
		restart=true;
		// Main loop displaying test progress and looking for property changes and starting/restarting session tasks
		while (restart) {
			randomFlag=true;

			stopSendFlag=false;
			try {
				stopReceiveFlag=ic.lookup(responseJndiName)==null;	// true if no receive queue
			} catch (NamingException e) {
				stopReceiveFlag=false;
			}

			long startTime=System.currentTimeMillis();
			int oldRecvCount=0;
			int oldSendCount=0;
			totalSendCount.set(0);
			totalSendTime.set(0);
			totalRecvCount.set(0);
			accpCount.set(0);
			rjctCount.set(0);
			
			if (restart) {
				// Restart can be set by the web application to restart the test (from receive after send status)
				sleep(ONESEC);	// Give tasks time to see flag and exit (restarted later)
				restart=false;
			}
			long lastPrintTime=startTime;			
			long printTime=startTime+TENSECS;
			logger.info("Test started sending "+targetMessageCount+" messages");
			int i=0;
			while(!stopSendFlag) {
				// Check if TPS and COUNT in properties and if changed in properties file every 5 secs - props override params
				if (i%5==0 && isUpdated(new File(jndiProperties))||i==0) {
					Properties props = new Properties();							
	                try {
	                	File propsFile=new File(jndiProperties);
	    				props.load(new FileInputStream(propsFile));
	                } catch (Exception ice) {
	                    logger.error("Update context properties "+ice);
	                    System.exit(1);
	                }						
					String tpsStr = props.getProperty("TPS");

					if (tpsStr!=null)
					try {
						int newTPS=Integer.parseInt(tpsStr);
						if (tps!=newTPS && tps>=0) {
							tps=newTPS;
							logger.info("Using TPS property "+tps);
						}
					} catch (Exception e) {};
					String countStr = props.getProperty("count");
					if (countStr!=null)
					try {
						int newCount=Integer.parseInt(countStr);
						if (targetMessageCount!=newCount) {
							targetMessageCount=newCount;
							logger.info("Using Count property "+targetMessageCount);
						}
					} catch (Exception e) {};
					String delayStr = props.getProperty("delay");
					if (delayStr!=null)
					try {
						int newDelay=Integer.parseInt(delayStr);
						if (delay!=newDelay) {
							delay=newDelay;
							logger.info("Using Delay property "+delay);
						}
					} catch (Exception e) {};
					String restartStr = props.getProperty("restart");
					if (restartStr!=null)
					try {
						int newRestart=Integer.parseInt(restartStr);
						if (threadRestart!=newRestart) {
							threadRestart=newRestart;
							logger.info("Using task restart property "+threadRestart);
						}
					} catch (Exception e) {};
					String receiveStr = props.getProperty("receiveaftersend");
					if (receiveStr!=null)
					try {
						boolean newReceive=Boolean.parseBoolean(receiveStr);
						if (receiveAfterSend!=newReceive) {
							receiveAfterSend=newReceive;
							logger.info("Using receive after send property "+receiveAfterSend);
						}
					} catch (Exception e) {};
				}
				// Check if the template has changed - can change values at run time - 10 secs
				if (i%10==0 && isUpdated(new File(templateFile))) {
					logger.info("Template file updated "+templateFile);
					docText=XMLutils.getTemplate(templateFile);
					msgDoc = XMLutils.bytesToDoc(docText);
				}					

				// Now all params set or updated - start the threads or restart failed threads 
				if (!stopSendFlag&&totalSendCount.get()<targetMessageCount) {
					int terminated=checkRunning(threads,threadRestarts,threadRestart);

					if (terminated==connectionCount) {
						stopSendFlag=true;
						logger.info("All sessions terminated - sending");
					}
				}
				
				sleep(ONESEC);

				if (totalSendCount.get()>=targetMessageCount) {
					stopSendFlag=true;
					logger.info("Send count "+targetMessageCount+" reached, actual count "+totalSendCount.get());
				}
				
				long now=System.currentTimeMillis();
				if (now > printTime || stopSendFlag) {
					long periodSecs=(now-lastPrintTime)/ONESEC;
					
					int diffRecv=totalRecvCount.get()-oldRecvCount;
					int diffSend=totalSendCount.get()-oldSendCount;
					if (periodSecs>0) {
						recvTps=(float)(diffRecv)/periodSecs;
						sendTps=(float)(diffSend)/periodSecs;
					}
					oldRecvCount=totalRecvCount.get();
					oldSendCount=totalSendCount.get();
					logger.info("Sessions "+sessionCount.get()+" connected "+curConnectedCount.get());
					logger.info("Sent "+diffSend+" total "+totalSendCount.get()+", TPS "+String.format("%.1f", sendTps));
					logger.info("Received "+diffRecv+" total "+totalRecvCount+", TPS "+String.format("%.1f", recvTps));
					if (accpCount.get()>0 || rjctCount.get()>0)
						logger.info("Accepted "+accpCount.get()+ " Rejected "+rjctCount);
					if (duplicateCount.get()>0)
						logger.info("Duplicates "+duplicateCount.get());
					logger.debug("Restart "+restart);
					lastPrintTime=now;
					printTime=now+TENSECS;
				}
				i++;
			}

			// Loop to receive late replies - stops if no replies if send count >= 0, goes on forever if send count < 0
			//if (count>=0&&!restart) threadRestart=0;	// Don't restart threads if closing
			oldRecvCount=totalRecvCount.get();
			int oldTerminated=0;
			boolean countChanged=true;
			while (!restart&&(countChanged||targetMessageCount<0||receiveAfterSend)) {
				sleep(ONESEC);
				long now=System.currentTimeMillis();
				if (countChanged||now>printTime) {
					long periodSecs=(now-lastPrintTime)/ONESEC;					
					int diffRecv=totalRecvCount.get()-oldRecvCount;
					if (periodSecs>0) {
						recvTps=(float)(diffRecv)/periodSecs;
					}
					logger.info("Sessions "+sessionCount.get()+" connected "+curConnectedCount.get());
					logger.info("Receive only - received: "+(totalRecvCount.get()-oldRecvCount) +
							" total "+totalRecvCount.get()+", TPS "+String.format("%.1f", recvTps));
					if (accpCount.get()>0 || rjctCount.get()>0)
						logger.info("Accepted "+accpCount.get()+ " Rejected "+rjctCount);
					if (duplicateCount.get()>0)
						logger.info("Duplicates "+duplicateCount.get());
					oldRecvCount=totalRecvCount.get();
					lastPrintTime=now;
					printTime=now+TENSECS;
				}
				countChanged=oldRecvCount!=totalRecvCount.get();
				
				if (!stopReceiveFlag) {	// Check if tasks need to be restarted
					int terminated=checkRunning(threads,threadRestarts,threadRestart);
					if (terminated==connectionCount&!restart) {
						logger.info("All sessions terminated - while receiving");
						break;
					}
				}
			}
			logger.info("Test run completed");
			logger.info(timeFormat.format(new Date()));
			logger.info("Total Sent "+totalSendCount.get());
			logger.info("Total Received "+totalRecvCount);
		}
		if (totalSendCount.get()>0) {
			float avSend=new Float(totalSendTime.get())/totalSendCount.get();
			logger.info(String.format("Av.send %.1f ms. Check app server console or log for details (%s)", avSend, new Date()));
		}
		// Closing sessions can take a long time - try for some time or until the tasks stop;
		logger.log(VERBOSE,"Exit after sessions close");
		int RETRYSECS=120;
		for (int i=0;i<RETRYSECS&&(checkRunning(threads,threadRestarts,0)<connectionCount);i++) {
			sleep(ONESEC);
		}
		logger.log(VERBOSE,"Sessions open "+(connectionCount-checkRunning(threads,threadRestarts,0)));			
		System.exit(0);	// This will kill the last tasks
	}
	
	public void run() {
		Connection connection=null;
		
		threadIndex=(int)threadMap.get(Thread.currentThread().getId());

		logger.log(VERBOSE,"Session started "+threadIndex);
		
		while (restart) {sleep(10);	// Wait in case main thread still restarting
			logger.log(VERBOSE,"Session waiting "+restart);
		}
		
		int mySession=sessionCount.incrementAndGet();

		try {
			
			connection = cf.createConnection();
			if (connection.getClientID()==null)
				connection.setClientID(System.getProperty("user.name")+"-"+threadIndex+"-"+System.currentTimeMillis());
			
			try {
				ActiveMQConnection acon=(ActiveMQConnection) connection;
				logger.log(VERBOSE,"ActiveMQ broker name: "+acon.getBrokerName());
				acon.addTransportListener(this);
			} catch (java.lang.ClassCastException e) {
				logger.debug("ActiveMQ transport listener not supported "+e);
			}
			try {
				org.apache.activemq.artemis.jms.client.ActiveMQConnection acon=(org.apache.activemq.artemis.jms.client.ActiveMQConnection) connection;
				logger.log(VERBOSE,"Artemis provider name: "+acon.getMetaData().getJMSProviderName()+" version "+acon.getMetaData().getProviderVersion());
				acon.setFailoverListener((FailoverEventListener) this);
			} catch (java.lang.ClassCastException e) {
				logger.debug("ActiveMQ Artemis failover listener not supported "+e);
			}
			connection.setExceptionListener(this);

			Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			Queue queue = (Queue)ic.lookup(destinationJndiName);

			MessageProducer publisher = session.createProducer(queue);
			MessageConsumer consumer=null;	
			Queue respQueue=null;
			try {
				respQueue=(Queue)ic.lookup(responseJndiName);
			} catch (Exception e) {};
			if (respQueue!=null && !respQueue.getQueueName().isEmpty()) {
				Session rsession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
				consumer = rsession.createConsumer(respQueue);
				consumer.setMessageListener(this);
			}
			connection.start();
			curConnectedCount.incrementAndGet();
			TextMessage message = session.createTextMessage();
			
			logger.info("Connected session "+threadIndex+" session count "+mySession);
	
			long startTime=System.currentTimeMillis();

	        int currentTPS=tps;
	        int oldSendCount=0;
	        
	        Document msgDoc = XMLutils.bytesToDoc(docText);
	        
	        threadStatus[threadIndex]="Started";
	
			while (totalSendCount.get()<targetMessageCount&&!stopSendFlag) { 
				// Check if messages have still to be sent this increment (other threads have not already done the TPS)
				if (totalSendCount.get()-oldSendCount<=currentTPS) {
					// Going to send so increment send count so other tasks do not start too many
					int sendCount=totalSendCount.incrementAndGet();
					// Set msgDoc test values...
					XMLutils.setElementValue(msgDoc,"MsgId",sendCount+"-"+startTime);
					XMLutils.setElementValue(msgDoc,"CreDtTm",dateTimeFormat.format(new Date()));
					XMLutils.setElementValue(msgDoc,"IntrBkSttlmDt",dateFormat.format(new Date()));
					dateTimeFormatGMT.setTimeZone(TimeZone.getTimeZone("GMT"));	// From java 8 new Instant().toString() available
					XMLutils.setElementValue(msgDoc,"AccptncDtTm",dateTimeFormatGMT.format(new Date())+"Z");
					String TxId="TX"+dateFormat.format(new Date())+" "+System.nanoTime();
					TxId=TxId.replaceAll("-", "")+sendCount;
					XMLutils.setElementValue(msgDoc,"TxId",TxId);
					XMLutils.setElementValue(msgDoc,"EndToEndId",TxId);					
					if (values!=null) {
						String vStr=values[randomNumbers.nextInt(values.length)];
						XMLutils.setElementValue(msgDoc,"TtlIntrBkSttlmAmt",vStr);
						XMLutils.setElementValue(msgDoc,"IntrBkSttlmAmt",vStr);
					}
					int did=-1;
					if (debtorBICs!=null) {	// If specified, override template value
						did=randomNumbers.nextInt(debtorBICs.length);
						String bic=debtorBICs[did];
			            XMLutils.setElementValue(XMLutils.getElement(msgDoc,"InstgAgt"),"BIC",bic);           
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"DbtrAgt"),"BIC",bic);           
					}
					int cid=-1;
					if (creditorBICs!=null) {	// If specified, override template value
						cid=randomNumbers.nextInt(creditorBICs.length);
						String bic=creditorBICs[cid];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"CdtrAgt"),"BIC",bic);
					}
					if (debtorIBANs!=null && debtorIBANs.length>0) {	// If specified, override template value
						if (did<0 || did>=debtorIBANs.length) did=randomNumbers.nextInt(debtorIBANs.length);
						String iban=debtorIBANs[did];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"DbtrAcct"),"IBAN",iban);           
					}
					if (creditorIBANs!=null && creditorIBANs.length>0) {	// If specified, override template value
						if (cid<0 || cid>=creditorIBANs.length) cid=randomNumbers.nextInt(creditorIBANs.length);
						String iban=creditorIBANs[cid];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"CdtrAcct"),"IBAN",iban);
					}

					long msgSent=System.currentTimeMillis();
					long sendTime=0;
					message.setText(XMLutils.documentToString(msgDoc));
					// Send message with retry to catch Artemis blocked message errors
					try {publisher.send(message);}
					catch (JMSException e) {
						if (e.toString().contains("AMQ219016")) {
							logger.warn("Retry on "+e);
							publisher.send(message);	// Might be a missed ack on failover (can cause a duplicate)
						} else
							throw e;	// Throw to normal JMS exception handling
					}
					sendTime=System.currentTimeMillis()-msgSent;
					totalSendTime.addAndGet(sendTime);
					
			        threadStatus[threadIndex]="Sending "+(++mySendCount)+" receiving "+myRecvCount;

					// Give other threads a chance and spread sends over a one second interval
					if (delay>0) {
						Thread.sleep(delay);	// delay from properties
					} else if (currentTPS>0){
						long currentDelay=curConnectedCount.get()*ONESEC/currentTPS-sendTime*2;
						logger.debug("Delay "+currentDelay);
						Thread.sleep(currentDelay>0?currentDelay:0);	// Calculated delay
					}
				} else {
					// TPS count met so sleep for the rest of the second
					oldSendCount=totalSendCount.get();
					long now=System.currentTimeMillis(); 
					long TPSsendTime=now-startTime;
					long sleepTime=ONESEC-TPSsendTime;
					Thread.sleep(sleepTime>0?sleepTime:0);
					// Restart the timer
					startTime=System.currentTimeMillis();
				}
	
				// Randomize tps if wanted
                if (range>0) {
                	int r=randomNumbers.nextInt(range);
                	if (randomFlag)
                		currentTPS=tps+r;
                	else
                		currentTPS=tps-r;
                	randomFlag=!randomFlag;
                	if (trace>0) logger.info("TPS adjusted "+currentTPS+" "+r);
                } else
                	currentTPS=tps;
			}
			logger.log(VERBOSE,"Session "+threadIndex+" total sent "+mySendCount+" now listening...");
			// Need to wait to stop receiving before closing connection and exiting
			boolean countChanged=true;
			int oldRecvCount=0;
			while (!restart && (countChanged||targetMessageCount<0||receiveAfterSend)) {
		        threadStatus[threadIndex]="Sent "+mySendCount+" receiving after send "+myRecvCount;
				Thread.sleep(ONESEC);
				countChanged=oldRecvCount!=totalRecvCount.get();
				if (countChanged) {
					oldRecvCount=totalRecvCount.get();						
				}
			}
			if (restart)
				threadStatus[threadIndex]="Exit for restart";
			else
				threadStatus[threadIndex]="Exit";
			logger.log(VERBOSE,"Session "+threadIndex+" closing");
			session.close();
			
		} catch (JMSException e) {
			logger.error("JMS error "+e);
	        threadStatus[threadIndex]="JMS error";
		} catch (NamingException e) {
			logger.error("Naming/lookup error "+e);
	        threadStatus[threadIndex]="Lookup error";
		} catch (Exception e1) {
			e1.printStackTrace();
		} finally {
			sessionCount.decrementAndGet();
			if (connection!=null)
    			curConnectedCount.decrementAndGet();
				try {
					connection.close();
				} catch (JMSException e) {
					e.printStackTrace();
				}
		}
		logger.log(VERBOSE,"Session "+threadIndex+" exit");
	}

	@Override
	public void onMessage(Message msg) {
		// Just count the received message - only used for the TPS count - and status checks
		totalRecvCount.incrementAndGet();
		myRecvCount++;
		if (stopStatus!=null||duplicateCheck)
		try {
			TextMessage tm = (TextMessage) msg;
			Document msgDoc = XMLutils.stringToDoc(tm.getText());
			if (stopStatus!=null&&msgDoc!=null) {
		        String status=XMLutils.getElementValue(msgDoc,"GrpSts");
		        String reason=null;
	          	Element rsnInf=XMLutils.getElement(msgDoc,"StsRsnInf");
	           	if (rsnInf!=null) reason=XMLutils.getElementValue(rsnInf,"Cd");
		        if (status==null) {
		        	logger.warn("Missing status");
		        } else {
		        	logger.log(VERBOSE,"Status "+status+" "+reason);
		        	if (status.equals("ACCP")) accpCount.incrementAndGet();
		        	if (status.equals("RJCT")) rjctCount.incrementAndGet();
		        	
		        	if (status.matches(stopStatus)) {
		        		if (stopReason==null || (reason!=null && reason.matches(stopReason))) {
		        			if (stopCount.incrementAndGet()>stopCountMax&&!stopSendFlag) {
		        				stopSendFlag=true;
		        				logger.warn("Stopping on status "+status+" "+reason);
		        			}
		        		}
		        	}
		        }
			}
	        if (duplicateCheck&&msgDoc!=null) {
	        	String TXID=XMLutils.getElementValue(msgDoc,"TxId");
	        	if (TXID!=null) {
		        	if (txList.contains(TXID)) {	// Was this already seen in the last TXs
		        		duplicateCount.getAndIncrement();
		        	} else {
		        		if (txList.size()>=10000) txList.removeFirst();	// Free space at start of list
		        		txList.add(TXID);	// Add to end of list
		        	}
	        	}
	        }
		} catch (JMSException e) {
			logger.warn("Message format not text");
		}
	}
	
	// JMS exception listener
    public synchronized void onException(JMSException ex) {
        logger.trace("Listener - JMS Exception: "+ex);
    }

	@Override
	public void onException(IOException exception) {
		logger.info("Transport IO Exception "+exception+" session "+threadIndex);		
	}
	
	@Override
	public void onCommand(Object command) {
		logger.log(Level.DEBUG,"Transport Command "+command.getClass()+" session "+threadIndex);
	}

	@Override
	public void transportInterupted() {
		logger.info("Transport Interrupted session "+threadIndex);		
	}

	@Override
	public void transportResumed() {
		logger.info("Transport Resumed session "+threadIndex);		
	}
	
	@Override
	public void failoverEvent(FailoverEventType type) {
		logger.info("Artemis failover session "+threadIndex+" "+type);		
	}
	
	static int checkRunning(Thread[]threads,int [] threadRestarts, int threadRestart) {
		int terminated=0;
		int running=0;
		for (int t=0;t<connectionCount;t++) {
			if (threads[t]==null) {
				// Not created yet so create
				IPTestCommand iptest=new IPTestCommand(); 
				threads[t]=new Thread(iptest);
				threadRestarts[t]=0;
				threadStatus[t]="start";
				threadMap.put(threads[t].getId(),t);	// Threads can lookup array index from its thread ID
			} else if (threads[t].getState()==Thread.State.TERMINATED) {
				if (threadRestart<0 || threadRestarts[t]<threadRestart) {
					// restart limit not reached or indefinite and terminated so restart
					threadMap.remove(threads[t].getId());
					IPTestCommand iptest=new IPTestCommand(); 
					threads[t]=new Thread(iptest);
					threadRestarts[t]++;
					threadStatus[t]="start";
					threadMap.put(threads[t].getId(),t);	// Threads can lookup array index from its thread ID
					logger.info("Session "+t+" restarted");
				};
				terminated++;
			} else {
				// running or blocked
				running++;
			}
			//sessionCount.set(running);
			logger.debug("State "+t+" "+threads[t].getState()+" "+threadStatus[t]);
		};
		for (int t=0;t<connectionCount;t++) {
			if (threadStatus[t].equals("start")) {
				threadStatus[t]="";
				threads[t].start();
				sleep(HUNDREDMILLIS);
				terminated--;
			}
		}
		return terminated;
	}
	
	static String[] getSessionsStatus() {
		String status[] = new String[connectionCount];
		for (int t=0;t<connectionCount;t++) {
			if (threads[t]==null) {
				status[t]="thread not started";
			} else {
				Integer i=threadMap.get(threads[t].getId());
				String taskStatus="";
				if (i!=null) taskStatus=threadStatus[i];
				status[t]=threads[t].getState().toString()+" restarts "+threadRestarts[t]+" "+taskStatus;
			}
		}
		return status;
	}
	
	private static Hashtable<String, Long> timeStampCache=new Hashtable<String, Long>();

	static boolean isUpdated( File file ) {
		long fileTimeStamp = file.lastModified();
		Long oldTimeStamp = (Long) timeStampCache.get(file.getAbsolutePath());
		if (oldTimeStamp==null)
			timeStampCache.put(file.getAbsolutePath(),new Long(fileTimeStamp));
		else
			timeStampCache.replace(file.getAbsolutePath(),new Long(fileTimeStamp));

		if (oldTimeStamp!=null && fileTimeStamp!=oldTimeStamp) {
			return true;
		}
		return false;
	}
	  
	static String[] getValueList(String parameter) {
		String list[]=null;
		  if (parameter!=null) {
			  if (parameter.contains(",")) {
			  // Convert parameter to list
			  list=parameter.split(",");
		  } else {
			  // Try if it is a file and if so convert to list
			  try {
				  Path fileName = Paths.get(parameter);
				  String ibans = new String(Files.readAllBytes(fileName),StandardCharsets.UTF_8);
				  list=ibans.split("\n");
			  } catch (IOException e) {
				  // Not a valid file so assume that it is a single value parameter
				  logger.trace("Bad file "+e);
					  list=new String [] {parameter};
				  }
			  }
		  }
		  return list;
	}
	
	static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception e) {};
	}

}

class Arguments {
	// Switches start with '_' which is replaced with '-' in the cli switch name
	// Targets do not start with '_' and are mapped to positional cli arguments	
	public String[] targets;	// Array of all targets
	
	public int 		_trace=0;
	public int 		_tps_range=0;
	public String 	_template=IPTestCommand.defaultTemplate;
	public String 	_creditor_bics;
	public String 	_debtor_bics;
	public String 	_properties="jndi.properties";
	public String 	_values;
	public String 	_creditor_ibans;
	public String 	_debtor_ibans;
	public int 		_stop_count=1;
	public boolean 	_receive_after_send;
	public String 	_stop_status;
	public boolean 	_duplicate_check=false;
	public boolean 	_h=false;
	public boolean 	_help=false;
	// Targets (positional arguments)
	public int count=10;
	public int tps=1;
}


