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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

public class IPTestCommand implements MessageListener {
	
	private static final Logger logger = LoggerFactory.getLogger(IPTestCommand.class);

    static final long ONESEC=1000;
    static final long TENSECS=10000;
    static final int DEFAULTCONNECTIONS=8;

	private static final String destinationJndiName = "instantpayments_mybank_originator_request";
	private static final String responseJndiName = "instantpayments_mybank_originator_response";
	public static int activeCount=0;
	public static AtomicInteger recvCount=new AtomicInteger(0);

	private static String defaultTemplate="pacs.008.xml"; 

	private static boolean stopFlag=false;

	public static void main (String args[]) {

		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");	// 15:25:40
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");	// 2018-12-28
		SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264
		SimpleDateFormat dateTimeFormatGMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264

        if (MyArgs.arg(args,"-h") || MyArgs.arg(args,"-?") || MyArgs.arg(args,"-help")) {
            System.out.println("IPTestCommand V1.0.4");
            System.out.println("Usage: <count> <tps> -template templatefile -values amounts -creditorbics bics -debtorbics bics -properties jndiprops");
            System.out.println("      -tpsrange n -creditoribans ibans -debtoribans ibans");
            System.out.println("Defaults are: count=10 tps=1 template=pacs.008.xml");
            System.out.println("Change the jndi.properties file to change the broker hostname or queue names.");
            System.out.println("'-h' or '-?' gives this help info.");
            System.out.println("The values and bics/ibans parameters are used to replace the template elements for testing purposes.");
            System.out.println("These parameters can be comma separated lists, elements are selected ramdomly. If a filename is given");
            System.out.println("instead of a list then the file should have one element one per line.");          
            System.out.println("The tpsrange argument can be used to vary the tps rate within a second for + or - of the given range.");
            System.out.println("If a zero count is used then the tool will not send messages and will receive messages until the queue is empty.");
            System.exit(0);
        }

		String countStr = MyArgs.arg(args,0,"10");
		int count=10;
		try {count=Integer.parseInt(countStr);} catch (Exception e) {}; 
		if (count<0) {
			logger.error("Bad count "+countStr);
			System.exit(1);
		}
		String tpsStr = MyArgs.arg(args,1,"1");
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
		String creditorBICsStr=MyArgs.arg(args,"-creditorbics",null);
		String debtorBICsStr=MyArgs.arg(args,"-debtorbics",null);
		String jndiProperties=MyArgs.arg(args,"-properties","jndi.properties");
		String valueStr = MyArgs.arg(args,"-values","10");
		String rangeStr = MyArgs.arg(args,"-tpsrange","0");
		String creditorIBANsStr = MyArgs.arg(args,"-creditoribans",null);
		String debtorIBANsStr = MyArgs.arg(args,"-debtoribans",null);

		String values[]=getValueList(valueStr);
		String creditorBICs[]=getValueList(creditorBICsStr);
		String debtorBICs[]=getValueList(debtorBICsStr);
		String creditorIBANs[]=getValueList(creditorIBANsStr);
		String debtorIBANs[]=getValueList(debtorIBANsStr);
		
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
			Context ic=null;
			ConnectionFactory cf;
			Connection connection[]=null;
			int connectionCount=DEFAULTCONNECTIONS;

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

				TextMessage message[];
				MessageProducer publisher[];
				
				String cntStr=props.getProperty("connectionCount");
				if (cntStr!=null)
				try {
					connectionCount=Integer.parseInt(cntStr);
					if (connectionCount<1) connectionCount=1;
				} catch (Exception e) {logger.info("ConnectionCount error "+e);};

                String connectionFactoryStr="ConnectionFactory";

                logger.info("getting Factory from jndi "+jndiProperties+" "+connectionFactoryStr);

				cf=(QueueConnectionFactory)ic.lookup(connectionFactoryStr);

                logger.debug("Connection Factory "+cf);
 
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
                        Session csession = connection[i].createSession(false, Session.AUTO_ACKNOWLEDGE);
						MessageConsumer consumer = csession.createConsumer(respQueue);
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
					// If valueStr is a list select a value
					String vStr=valueStr;
					if (values!=null) {
						vStr=values[randomNumbers.nextInt(values.length)];					
					}
					// Set msgDoc test values...
					XMLutils.setElementValue(msgDoc,"MsgId",totalSendCount+"-"+startTime);
					XMLutils.setElementValue(msgDoc,"CreDtTm",dateTimeFormat.format(new Date()));
					XMLutils.setElementValue(msgDoc,"IntrBkSttlmDt",dateFormat.format(new Date()));
					XMLutils.setElementValue(msgDoc,"TtlIntrBkSttlmAmt",vStr);
					dateTimeFormatGMT.setTimeZone(TimeZone.getTimeZone("GMT"));	// From java 8 new Instant().toString() available
					XMLutils.setElementValue(msgDoc,"AccptncDtTm",dateTimeFormatGMT.format(new Date())+"Z");
					XMLutils.setElementValue(msgDoc,"IntrBkSttlmAmt",vStr);
					String TxId="TX"+dateFormat.format(new Date())+" "+System.nanoTime();
					TxId=TxId.replaceAll("-", "");
					XMLutils.setElementValue(msgDoc,"TxId",TxId);
					XMLutils.setElementValue(msgDoc,"EndToEndId",TxId);
					if (debtorBICs!=null) {	// If specified, override template value
						String bic=debtorBICs[randomNumbers.nextInt(debtorBICs.length)];
			            XMLutils.setElementValue(XMLutils.getElement(msgDoc,"InstgAgt"),"BIC",bic);           
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"DbtrAgt"),"BIC",bic);           
					}
					if (creditorBICs!=null) {	// If specified, override template value
						String bic=creditorBICs[randomNumbers.nextInt(creditorBICs.length)];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"CdtrAgt"),"BIC",bic);
					}
					if (debtorIBANs!=null && debtorIBANs.length>0) {	// If specified, override template value
						String iban=debtorIBANs[randomNumbers.nextInt(debtorIBANs.length)];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"DbtrAcct"),"IBAN",iban);           
					}
					if (creditorIBANs!=null && creditorIBANs.length>0) {	// If specified, override template value
						String iban=creditorIBANs[randomNumbers.nextInt(creditorIBANs.length)];
		            	XMLutils.setElementValue(XMLutils.getElement(msgDoc,"CdtrAcct"),"IBAN",iban);
					}

					message[c].setText(XMLutils.documentToString(msgDoc));
					publisher[c].send(message[c]);
					totalSendCount++;
					if (trace>0 && totalSendCount%trace==0) logger.info("Message sent "+totalSendCount+" "+XMLutils.getElementValue(msgDoc,"CreDtTm"));
					sleepSendCount++;
					try{	// Sleep if not late (sleep until next send needed for TPS)
    					long now=System.currentTimeMillis(); 
    					long sendTime=now-startTime;
                     	if (sleepSendCount==currentTPS) {
                     		sleepSendCount=0;
							// Sleep for rest of second if TPS already delivered
    						totalSendTime=totalSendTime+sendTime;
                            if (sendTime<ONESEC)
							    Thread.sleep(ONESEC-sendTime);
							startTime=System.currentTimeMillis();
                            // Set current tps to new target tps for next cycle if a range has been specified
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
                        } else {
                        	long timeLeft=ONESEC-sendTime;
							if (timeLeft>100) {
		                     	int remainingTps=currentTPS-sleepSendCount;
 								Thread.sleep(timeLeft/remainingTps);	// Divide remaining TPS over time
							} else
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
							recvTps=(recvCount.get()-oldRecvCount)/periodSecs;
							sendTps=(totalSendCount-oldSendCount)/periodSecs;
						}
						oldRecvCount=recvCount.get();
						oldSendCount=totalSendCount;
						logger.info(timeFormat.format(new Date()));
						logger.info("Sent "+totalSendCount+", TPS "+sendTps);
						logger.info("Received "+recvCount+", TPS "+recvTps);
						printTime=now+TENSECS;
					}
					// Check if TPS and COUNT properties changed in properties file every 5 secs
					if (i%5==0 && isUpdated(new File(jndiProperties))) {
		                try {
		                	propsFile=new File(jndiProperties);
		    				props.load(new FileInputStream(propsFile));
		                } catch (Exception ice) {
		                    logger.error("Update context properties "+ice);
		                    System.exit(1);
		                }						
						tpsStr = props.getProperty("TPS");

						if (tpsStr!=null)
						try {
							int newTPS=Integer.parseInt(tpsStr);
							if (tps!=newTPS) {
								tps=newTPS;
								logger.info("NEW TPS property "+tps);
							}
						} catch (Exception e) {};
						if (tps<1) {
							break;
						}
						countStr = props.getProperty("COUNT");
						if (countStr!=null)
						try {
							int newCount=Integer.parseInt(countStr);
							if (count!=newCount) {
								count=newCount;
								logger.info("NEW Count property "+count);
							}
						} catch (Exception e) {};
					}
				}
				oldRecvCount=recvCount.get();
				boolean countChanged=true;
				while (!stopFlag && countChanged) {
					Thread.sleep(ONESEC);
					countChanged=oldRecvCount!=recvCount.get();
					if (countChanged) {
						logger.info(timeFormat.format(new Date()));
						logger.info("Receiving messages after send "+(recvCount.get()-oldRecvCount));
						oldRecvCount=recvCount.get();						
					}
				}

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
				if (connection!=null)
				for (int i=0;i<connectionCount;i++)
				if (connection[i]!=null) {
					try {
						connection[i].close();
					} catch (JMSException e) {
						throw new RuntimeException(e);
					}
				}
			}
			logger.info(timeFormat.format(new Date()));
			logger.info("Total Sent "+totalSendCount);
			logger.info("Total Received "+recvCount);
		}
		if (totalSendCount>0) {
			float avSend=new Float(totalSendTime)/totalSendCount;
			logger.info(String.format("Av.send %.1f ms. Check app server console or log for details (%s)\n", avSend, new Date()));
		}
		activeCount--;
	}

	@Override
	public void onMessage(Message msg) {
			recvCount.incrementAndGet();
	}
	
	  private static long oldTimeStamp;

	  static boolean isUpdated( File file ) {
		  long fileTimeStamp = file.lastModified();
		  if (fileTimeStamp!=oldTimeStamp) {
			  oldTimeStamp=fileTimeStamp;
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
				  // Try if it is a file and if so convert to 
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

}

