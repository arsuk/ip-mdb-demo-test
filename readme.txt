ip-mdb-test
===========

This is a simple class that generates pacs.008 messages to test Instant Payments. It
also consumes the pacs.002 responses.
It is an improvement on existing tools because it is a Java tool that can create
JMS messages with correct properties in the same way as real customers do. Furthermore,
the tool sends to and receives from multiple connections. There are therefore multiple
producers and consumers which is again what real clients do.
While the command is designed to send pacs.008 messages to an
instantpayments_mybank_originator_payment_request queue and receive pacs.002 messages
from an instantpayments_mybank_originator_payment_response queue it can send any text
message types, simply use another template and define other queues.
It is a JMS tool and can run with ActiveMQ or Artemis depending on the class definitions in the
jndi.properties file.

The command line is:

java -cp ip-mdb-test-1.0.jar IPTestCommand

Usage: <count> <tps> -template templatefile -values amounts -creditorbics bics -debtorbics bics -properties jndiprops
       -tpsrange n -creditoribans ibans -debtoribans ibans
Defaults are: count=10 tps=10 template=pacs.008.xml
Change the jndi.properties file to change the broker hostname or add selectable queue names.
'-h' or '-?' gives this help info.
The values and bics/ibans parameters are used to replace the template elements for testing purposes. These parameters
can be comma separated lists, elements are selected ramdomly. If a filename is given instead of a list then the file
should have one element one per line. 
The tpsrange argument can be used to vary the tps rate within a second for + or - of the given range.
After requested number of transactions have been sent the tool will wait for replies until 10s after the reply
queue is empty. 
If a zero send count is used then the tool will skip sending messages and receive messages until the queue is
empty. This can be used to clear the response queue after a test has been aborted early.
If no response queue is defined then the tool can only send messages. Queue names and connection info are defined
in the jndi properties file. An example is provided.
