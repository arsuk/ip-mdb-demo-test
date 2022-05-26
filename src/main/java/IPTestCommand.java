import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class IPTestCommand implements MessageListener,Runnable {
	
	private static final Logger logger = LoggerFactory.getLogger(IPTestCommand.class);

    static final long ONESEC=1000;
    static final long TENSECS=10000;
    static final int DEFAULTCONNECTIONS=8;

	private static final String destinationJndiName = "instantpayments_mybank_originator_request";
	private static final String responseJndiName = "instantpayments_mybank_originator_response";

	private static String defaultTemplate="pacs.008.xml"; 

	private static boolean stopFlag=false;
	
	private static Context ic=null;
	private static ConnectionFactory cf;
	private static byte[] docText;
	private static String[] values;
	private static String creditorBICs[];
	private static  String debtorBICs[];
	private static String creditorIBANs[];
	private static String debtorIBANs[];
	private static String stopStatus;
	private static String stopReason;

	static 	Random randomNumbers=new Random();
	static AtomicInteger totalSendCount=new AtomicInteger(0);
	static AtomicLong totalSendTime=new AtomicLong(0);
	static AtomicInteger recvCount=new AtomicInteger(0);
	static AtomicInteger sessionCount=new AtomicInteger(0);
	static AtomicInteger stopCount=new AtomicInteger(0);
	static AtomicInteger accpCount=new AtomicInteger(0);
	static AtomicInteger rjctCount=new AtomicInteger(0);
	
	static int range;
	static int trace;
	static boolean randomFlag;
	static int tps;
	static int count;
	static int connectionCount=DEFAULTCONNECTIONS;
	static int stopCountMax;
	static int delay=0;
	private static SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");	// 15:25:40
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");	// 2018-12-28
	private static SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264
	private static SimpleDateFormat dateTimeFormatGMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");	// 2018-12-28T15:25:40.264

	public static void main (String args[]) {

        if (MyArgs.arg(args,"-h") || MyArgs.arg(args,"-?") || MyArgs.arg(args,"-help")) {
            System.out.println("IPTestCommand V1.1.0");
            System.out.println("Usage: <count> <tps> -template templatefile -values amounts -creditorbics bics -debtorbics bics -properties jndiprops");
            System.out.println("      -tpsrange n -creditoribans ibans -debtoribans ibans -stopstatus status -stopcount n");
            System.out.println("Defaults are: count=10 tps=1 template=pacs.008.xml");
            System.out.println("Change the jndi.properties file to change the broker hostname or queue names.");
            System.out.println("'-h' or '-?' gives this help info.");
            System.out.println("The values and bics/ibans parameters are used to replace the template elements for testing purposes.");
            System.out.println("These parameters can be comma separated lists, elements are selected randomly. If a filename is given");
            System.out.println("instead of a list then the file should have one element one per line.");          
            System.out.println("The tpsrange argument can be used to vary the tps rate within a second for + or - of the given range.");
            System.out.println("If count is zero then the tool will not send messages and will receive messages until the queue is empty (-1 forever).");
            System.out.println("Stop status is used to check the response status and stop the test if it matches after a stop count (default 1).");
            System.out.println("The stop status is a comma seperated string for status and reason, e.g. 'ACCP' or 'RJCT,AB05', regex syntax supported.");
            System.exit(0);
        }

		String countStr = MyArgs.arg(args,0,"10");
		count=10;
		try {count=Integer.parseInt(countStr);} catch (Exception e) {}; 
		if (count<=0) {
			logger.error("Send count zero, will only wait while replies in queue, or negative, will wait for replies indefintely "+countStr);
		}
		String tpsStr = MyArgs.arg(args,1,"1");
		tps=1;
		try {tps=Integer.parseInt(tpsStr);} catch (Exception e) {}; 
		if (tps<0) {
			logger.error("Bad tps "+tpsStr);
			System.exit(1);
		}
		String traceStr = MyArgs.arg(args,"-trace","0");
		trace=0;
		try {trace=Integer.parseInt(traceStr);} catch (Exception e) {};
		String templateFile = MyArgs.arg(args,"-template",defaultTemplate);
		String creditorBICsStr=MyArgs.arg(args,"-creditorbics",null);
		String debtorBICsStr=MyArgs.arg(args,"-debtorbics",null);
		String jndiProperties=MyArgs.arg(args,"-properties","jndi.properties");
		String valueStr = MyArgs.arg(args,"-values",null);
		String rangeStr = MyArgs.arg(args,"-tpsrange","0");
		String creditorIBANsStr = MyArgs.arg(args,"-creditoribans",null);
		String debtorIBANsStr = MyArgs.arg(args,"-debtoribans",null);
		stopStatus=MyArgs.arg(args,"-stopstatus",null);
		if (stopStatus!=null) {
			String s[]=stopStatus.split(",");
			if (s.length>1) {
				stopStatus=s[0];
				stopReason=s[1];
			} else
				stopReason=null;
		}
		String stopCountStr=MyArgs.arg(args,"-stopcount","1");
		try {stopCountMax=Integer.parseInt(stopCountStr);} catch (Exception e) {};

		values=getValueList(valueStr);
		creditorBICs=getValueList(creditorBICsStr);
		debtorBICs=getValueList(debtorBICsStr);
		creditorIBANs=getValueList(creditorIBANsStr);
		debtorIBANs=getValueList(debtorIBANsStr);
		int threadRestart=0;
		
		range=0;
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

		docText=XMLutils.getTemplate(templateFile);

		Document msgDoc = XMLutils.bytesToDoc(docText);
		
		if (valueStr==null) valueStr=XMLutils.getElementValue(msgDoc,"IntrBkSttlmAmt");
		
		randomFlag=true;

		stopFlag=false;

		if (msgDoc==null)
			logger.error("Missing document template "+templateFile);
		else {
			Connection connection[]=null;

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
				if (countStr!=null)
				try {
					connectionCount=Integer.parseInt(connectionCountStr);
				} catch (Exception e) {logger.warn("Bad connectionCount property");};
									
				String resp="IPTestCommand: "+templateFile+" Value: "+valueStr+" Count: "+count+" TPS: "+tps+" +/- "+range;
				logger.info(resp+"\n");

                String connectionFactoryStr="ConnectionFactory";

                logger.info("Getting Factory from jndi "+jndiProperties+" "+connectionFactoryStr);

				cf=(QueueConnectionFactory)ic.lookup(connectionFactoryStr);

                logger.debug("Connection Factory "+cf);
 
				logger.info("Starting "+connectionCount+" sessions");
				
				Thread threads[] = new Thread[connectionCount];
				int threadRestarts[] = new int [connectionCount];
				for (int t=0;t<connectionCount;t++) {
					IPTestCommand iptest=new IPTestCommand(); 
					threads[t]=new Thread(iptest);
					threads[t].start();
					threadRestarts[t]=0;
				};

				long startTime=System.currentTimeMillis();
				int oldRecvCount=0;
				int oldSendCount=0;
				long printTime=startTime+TENSECS;

				int i=0;
				while(!stopFlag) {
					// Check if TPS and COUNT in properties and if changed in properties file every 5 secs - props override params
					if (i%5==0 && isUpdated(new File(jndiProperties))||i==0) {
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
								logger.info("Using TPS property "+tps);
							}
						} catch (Exception e) {};
						if (tps<1) {
							break;
						}
						countStr = props.getProperty("count");
						if (countStr!=null)
						try {
							int newCount=Integer.parseInt(countStr);
							if (count!=newCount) {
								count=newCount;
								logger.info("Using Count property "+count);
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
					}
					// Check if the template has changed - can change values at run time - 10 secs
					if (i%10==0 && isUpdated(new File(templateFile))) {
						logger.info("Template file updated "+templateFile);
						docText=XMLutils.getTemplate(templateFile);
						msgDoc = XMLutils.bytesToDoc(docText);
					}					
					Thread.sleep(ONESEC);

					if (totalSendCount.get()>=count) {
						stopFlag=true;
						logger.info("Send count "+count+" reached, currently "+totalSendCount.get());
					}
					
					int terminated=0;
					for (int t=0;t<connectionCount;t++) {
						if (threads[t].getState()==Thread.State.TERMINATED) {
							if (!stopFlag && (threadRestart<0 || threadRestarts[t]<threadRestart)) {
								IPTestCommand iptest=new IPTestCommand(); 
								threads[t]=new Thread(iptest);
								threads[t].start();
								threadRestarts[t]++;
								logger.info("Session "+t+" restarted");
							} else
								terminated++;
						}
						logger.debug("State "+threads[t].getState());
					};
					sessionCount.set(connectionCount-terminated);	// Update running count for tps calculation
					if (terminated==connectionCount) {
						stopFlag=true;
						logger.info("All sessions terminated");
					}
					
					long now=System.currentTimeMillis();
					if (now > printTime || stopFlag) {
						long periodSecs=(now-printTime+TENSECS)/ONESEC;
						long recvTps=0;
						long sendTps=0;
						if (periodSecs>0) {
							recvTps=(recvCount.get()-oldRecvCount)/periodSecs;
							sendTps=(totalSendCount.get()-oldSendCount)/periodSecs;
						}
						oldRecvCount=recvCount.get();
						oldSendCount=totalSendCount.get();
						logger.info("Sessions "+sessionCount.get()+" "+timeFormat.format(new Date()));
						logger.info("Sent "+totalSendCount.get()+", TPS "+sendTps);
						logger.info("Received "+recvCount+", TPS "+recvTps);
						if (accpCount.get()>0 || rjctCount.get()>0)
							logger.info("Accepted "+accpCount.get()+ " Rejected "+rjctCount);
						printTime=now+TENSECS;
					}
					i++;
				}

				// Loop to receive late replies - stops it no replies - goes on forever if send count < 0
				oldRecvCount=recvCount.get();
				boolean countChanged=true;
				while (countChanged||count<0) {
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
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				if (connection!=null)
				for (int i=0;i<connectionCount;i++)
				if (connection[i]!=null) {
					try {
						connection[i].close();
					} catch (JMSException e) {
						logger.error("Close error "+e);
					}
				}
			}
			logger.info("Test run completed");
			logger.info(timeFormat.format(new Date()));
			logger.info("Total Sent "+totalSendCount.get());
			logger.info("Total Received "+recvCount);
		}
		if (totalSendCount.get()>0) {
			float avSend=new Float(totalSendTime.get())/totalSendCount.get();
			logger.info(String.format("Av.send %.1f ms. Check app server console or log for details (%s)\n", avSend, new Date()));
		}
	}
	
	public void run() {
		Connection connection=null;
		try {
			connection = cf.createConnection();

			Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			Queue queue = (Queue)ic.lookup(destinationJndiName);
	
			MessageProducer publisher = session.createProducer(queue);
	
			Queue respQueue=null;
			try {
				respQueue=(Queue)ic.lookup(responseJndiName);
			} catch (Exception e) {};
			if (respQueue!=null && !respQueue.getQueueName().isEmpty()) {
	            Session csession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
				MessageConsumer consumer = csession.createConsumer(respQueue);
				consumer.setMessageListener(new IPTestCommand());
			}
			connection.start();
			TextMessage message = session.createTextMessage();
			
			int rs=randomNumbers.nextInt((int)ONESEC);
			
			logger.info("Connected Session "+sessionCount.incrementAndGet());
			
			// Random start to avoid all tasks sending are once
			Thread.sleep(rs);
	
			long startTime=System.currentTimeMillis();

	        int currentTPS=tps;
	        int oldSendCount=0;
	        
	        Document msgDoc = XMLutils.bytesToDoc(docText);
	
			while (totalSendCount.get()<count&&!stopFlag) {
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
					publisher.send(message);
					sendTime=System.currentTimeMillis()-msgSent;
					totalSendTime.addAndGet(sendTime);

					// Give other threads a chance and spread sends over a one sec interval
					if (delay>0) {
						Thread.sleep(delay);	// delay from properties
					} else {
						long currentDelay=sessionCount.get()*ONESEC/currentTPS-sendTime*2;
						logger.debug("Delay "+currentDelay);
						Thread.sleep(currentDelay>0?currentDelay:0);	// Calculated delay
					}
				} else {
					// TPS met so sleep for the rest of the second
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
			// Need to wait to stop receiving before closing connections
			boolean countChanged=true;
			int oldRecvCount=0;
			while (countChanged||count<0) {
				Thread.sleep(ONESEC);
				countChanged=oldRecvCount!=recvCount.get();
				if (countChanged) {
					oldRecvCount=recvCount.get();						
				}
			}
		} catch (JMSException e) {
			logger.error("JMS error "+e);
		} catch (NamingException e) {
			logger.error("Naming/lookup error "+e);
		} catch (Exception e1) {
			e1.printStackTrace();
		} finally {
			if (connection!=null)
				try {
					connection.close();
				} catch (JMSException e) {
					e.printStackTrace();
				}
		}
		logger.debug("EXIT");
	}

	@Override
	public void onMessage(Message msg) {
		// Just count the received message - only used for the TPS count - message content ignored
		recvCount.incrementAndGet();
		
		TextMessage tm = (TextMessage) msg;
		Document msgDoc;
		if (stopStatus!=null)
		try {
			msgDoc = XMLutils.stringToDoc(tm.getText());
	        String status=XMLutils.getElementValue(msgDoc,"GrpSts");
	        String reason=null;
          	Element rsnInf=XMLutils.getElement(msgDoc,"StsRsnInf");
           	if (rsnInf!=null) reason=XMLutils.getElementValue(rsnInf,"Cd");
	        if (status==null) {
	        	logger.warn("Missing status");
	        } else {
	        	logger.debug("Status "+status+" "+reason);
	        	if (status.equals("ACCP")) accpCount.incrementAndGet();
	        	if (status.equals("RJCT")) rjctCount.incrementAndGet();
	        	
	        	if (status.matches(stopStatus)) {
	        		if (stopReason==null || (reason!=null && reason.matches(stopReason))) {
	        			if (stopCount.incrementAndGet()>stopCountMax&&!stopFlag) {
	        				stopFlag=true;
	        				logger.warn("Stopping on status "+status+" "+reason);
	        			}
	        		}
	        	}
	        }
		} catch (JMSException e) {
			logger.warn("Message format not text");
		}

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

}

