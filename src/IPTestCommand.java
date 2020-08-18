import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.Context;
import javax.naming.InitialContext;

import java.io.FileInputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;

public class IPTestCommand implements MessageListener {
	
	private static final Logger logger = LoggerFactory.getLogger(IPTestCommand.class);

    static final long ONESEC=1000;
    static final long TENSECS=10000;
    static final int DEFAULTCONNECTIONS=8;

	private static final String destinationJndiName = "instantpayments_mybank_originator_request";
	private static final String responseJndiName = "instantpayments_mybank_originator_response";
	public static int activeCount=0;
	public static int recvCount=0;

	private static String defaultTemplate="pacs.008.xml"; 

	private static boolean stopFlag=false;

	public static void main (String args[]) {

		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");	// 15:25:40
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");	// 2018-12-28
		SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264
		SimpleDateFormat dateTimeFormatGMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264

        if (MyArgs.arg(args,"-h") || MyArgs.arg(args,"-help")) {
            logger.info("IPTestCommand V1.0.1");
            logger.info("Usage: <count> <tps> -template templatefile -value amount -creditorbic bic -debtorbic bic -properties jndiprops -range n");
            logger.info("Defaults are: count=10 tps=10 template=pacs.008.xml");
            logger.info("Change the jndi.properties file to change the broker hostname or queue names.");
            logger.info("'-h' or '-?' gives this help info.");
            logger.info("The value and bic parameters can be used to replace the template elements for testing purposes.");
            logger.info("The -range arument can be used to vary the tps rate within the + or - tps of the given range.");
            logger.info("If a zero count is used then the tool will not send messages and will receive messages until the queue is empty.");
            System.exit(0);
        }

		String countStr = MyArgs.arg(args,0,"10");
		int count=10;
		try {count=Integer.parseInt(countStr);} catch (Exception e) {}; 
		if (count<0) {
			logger.error("Bad count "+countStr);
			System.exit(1);
		}
		String tpsStr = MyArgs.arg(args,1,"10");
		int tps=10;
		try {tps=Integer.parseInt(tpsStr);} catch (Exception e) {}; 
		if (tps<1) {
			logger.error("Bad tps "+tpsStr);
			System.exit(1);
		}
		String traceStr = MyArgs.arg(args,"-trace","0");
		int trace=0;
		try {trace=Integer.parseInt(traceStr);} catch (Exception e) {};
		String templateFile = MyArgs.arg(args,"-template",defaultTemplate);
		String creditorBIC=MyArgs.arg(args,"-creditorbic",null);
		String debtorBIC=MyArgs.arg(args,"-debtorbic",null);
		String jndiProperties=MyArgs.arg(args,"-properties","jndi.properties");
		String valueStr = MyArgs.arg(args,"-value","10");
		String rangeStr = MyArgs.arg(args,"-range","0");
		int range=0;
		try {range=Integer.parseInt(rangeStr);} catch (Exception e) {};
		if (range<0 || range>=tps) {
			logger.error("Bad range "+rangeStr);
			System.exit(1);
		}
		// Handle interrupts and stop gracefully if possible if we want to cut short the test
		Runtime.getRuntime().addShutdownHook(new Thread() {
	        public void run() {
	        	//logger.info("Interrupted");
            	stopFlag=true;
            	try {Thread.sleep(2000);}
            	catch (Exception e) {logger.info("Bad interrupt");}
	        }
	    });

		byte[] docText=XMLutils.getTemplate(templateFile);

		Document msgDoc = XMLutils.bytesToDoc(docText);
		
		Random randomNumbers=new Random();
		boolean randomFlag=true;

		String resp="IPTestCommand: "+templateFile+" Value: "+valueStr+" Count: "+count+" TPS: "+tps+" +/- "+range;
		logger.info(resp+"\n");

		stopFlag=false;
		int totalSendCount=0;
		long totalSendTime=0;
		if (msgDoc==null)
			logger.error("Missing document template "+templateFile);
		else {
			Context ic;
			ConnectionFactory cf;
			Connection connection[]=null;
			int connectionCount=DEFAULTCONNECTIONS;

			try {
				Properties props=new Properties ();
				props.load(new FileInputStream(jndiProperties));
				ic = new InitialContext(props);
				TextMessage message[];
				MessageProducer publisher[];
				
				String cntStr=props.getProperty("connectionCount");
				if (cntStr!=null)
				try {
					connectionCount=Integer.parseInt(cntStr);
					if (connectionCount<1) connectionCount=1;
				} catch (Exception e) {logger.info("ConnectionCount error "+e);};

				String CommandHost=System.getProperty("ActiveMQhostStr");
				if (CommandHost!=null) {
					logger.info("Using "+CommandHost);
					cf=new ActiveMQConnectionFactory(CommandHost);
				} else {
					cf=(QueueConnectionFactory)ic.lookup("queueConnectionFactory");
				}
				connection=new Connection[connectionCount];
				message=new TextMessage[connectionCount];
				publisher=new MessageProducer[connectionCount];
				// Context lookup - JNDI name of destination must be in the ActiveMQ resource-adapter as a admin-object, or
				// it must be messaging server subsystem as a jms-queue with JBoss / wildfly embedded AMQ
				for (int i=0;i<connectionCount;i++) {
					connection[i] = cf.createConnection();
					Session session = connection[i].createSession(false, Session.AUTO_ACKNOWLEDGE);
					Queue queue = (Queue)ic.lookup(destinationJndiName);

					publisher[i] = session.createProducer(queue);

					Queue respQueue=null;
					try {
						respQueue=(Queue)ic.lookup(responseJndiName);
					} catch (Exception e) {};
					if (respQueue!=null) {
						MessageConsumer consumer = session.createConsumer(respQueue);
						consumer.setMessageListener(new IPTestCommand());
					}
					connection[i].start();
					message[i] = session.createTextMessage();
				}
				logger.info("Connected "+connectionCount);

				long startTime=System.currentTimeMillis();
				long printTime=startTime+TENSECS;
                int currentTPS=tps;
                int c=0;
                int oldSendCount=0;
                int oldRecvCount=0;
                int sleepSendCount=0;

				for (int i=0;i<count&&!stopFlag;i++) {
					// Set msgDoc test values...
					XMLutils.setElementValue(msgDoc,"MsgId",totalSendCount+"-"+startTime);
					XMLutils.setElementValue(msgDoc,"CreDtTm",dateTimeFormat.format(new Date()));
					XMLutils.setElementValue(msgDoc,"IntrBkSttlmDt",dateFormat.format(new Date()));
					XMLutils.setElementValue(msgDoc,"TtlIntrBkSttlmAmt",valueStr);
					dateTimeFormatGMT.setTimeZone(TimeZone.getTimeZone("GMT"));	// From java 8 new Instant().toString() available
					XMLutils.setElementValue(msgDoc,"AccptncDtTm",dateTimeFormatGMT.format(new Date())+"Z");
					XMLutils.setElementValue(msgDoc,"IntrBkSttlmAmt",valueStr);
					String TxId="TX"+dateFormat.format(new Date())+" "+System.nanoTime();
					TxId=TxId.replaceAll("-", "");
					XMLutils.setElementValue(msgDoc,"TxId",TxId);
					XMLutils.setElementValue(msgDoc,"EndToEndId",TxId);
					if (debtorBIC!=null) {	// If specified, override template value
			            XMLutils.setElementValue(XMLutils.getElement(msgDoc,"InstgAgt"),"BIC",debtorBIC);           
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"DbtrAgt"),"BIC",debtorBIC);           
					}
					if (creditorBIC!=null)	// If specified, override template value          
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"CdtrAgt"),"BIC",creditorBIC);

					message[c].setText(XMLutils.documentToString(msgDoc));
					publisher[c].send(message[c]);
					totalSendCount++;
					if (trace>0 && totalSendCount%trace==0) logger.info("Message sent "+totalSendCount+" "+XMLutils.getElementValue(msgDoc,"CreDtTm"));
					sleepSendCount++;
					try{	// Sleep if not late (sleep until next send needed for TPS)
                     	int remainingTps=currentTPS-sleepSendCount;
                     	if (remainingTps==0) {
                     		sleepSendCount=0;
        					long now=System.currentTimeMillis(); 
        					long sendTime=now-startTime;
							// Sleep for rest of second if TPS already delivered
    						totalSendTime=totalSendTime+sendTime;
                            if (sendTime<ONESEC)
							    Thread.sleep(ONESEC-sendTime);
							startTime=System.currentTimeMillis();
                            // Set current tps to new target for next cycle if a range has been specified
                            if (range>0) {
                            	int r=randomNumbers.nextInt(range);
                            	if (randomFlag)
                            		currentTPS=tps+r;
                            	else
                            		currentTPS=tps-r;
                            	randomFlag=!randomFlag;
                            	if (trace>0) logger.info("TPS adjusted "+currentTPS+" "+r);
                            }
                        } else {  
						    Thread.sleep(0);
                        }
					} catch (Exception e) {};
					c++;
					if (c>=connectionCount) c=0;
					long now=System.currentTimeMillis();
					if (now > printTime || totalSendCount==count) {
						long periodSecs=(now-printTime+TENSECS)/ONESEC;
						long recvTps=0;
						long sendTps=0;
						if (periodSecs>0) {
							recvTps=(recvCount-oldRecvCount)/periodSecs;
							sendTps=(totalSendCount-oldSendCount)/periodSecs;
						}
						oldRecvCount=recvCount;
						oldSendCount=totalSendCount;
						logger.info(timeFormat.format(new Date()));
						logger.info("Sent "+totalSendCount+", TPS "+sendTps);
						logger.info("Received "+recvCount+", TPS "+recvTps);
						printTime=now+TENSECS;
					}
				}
				oldRecvCount=0;
				while (!stopFlag && recvCount-oldRecvCount>0) {
					Thread.sleep(TENSECS);
					logger.info(timeFormat.format(new Date()));
					logger.info("Received Messages "+recvCount);
					oldRecvCount=recvCount;
				}
				logger.info(timeFormat.format(new Date()));
				logger.info("Total Sent "+totalSendCount);
				logger.info("Total Received "+recvCount);

			} catch (javax.naming.NameNotFoundException e) {
				logger.error("Context lookup exception: "+e);
			} catch (javax.jms.IllegalStateException e) {
				logger.error("Closing: "+e);
			} catch (javax.jms.ResourceAllocationException e) {
				logger.error(""+e);
			} catch (javax.jms.JMSSecurityException e) {
				logger.error(""+e);
			} catch (javax.jms.JMSException e) {
				logger.error(""+e);
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				for (int i=0;i<connectionCount;i++)
				if (connection[i] !=null) {
					try {
						connection[i].close();
					} catch (JMSException e) {
						throw new RuntimeException(e);
					}
				}
			}

		}
		if (totalSendCount>0) {
			float avSend=new Float(totalSendTime)/totalSendCount;
			logger.info(String.format("Sent %d. Av.send %.1f ms. Check app server console or log for details (%s)\n", totalSendCount, avSend, new Date()));
		}
		activeCount--;
	}

	@Override
	public void onMessage(Message msg) {
		recvCount++;		
		
	}	

}

