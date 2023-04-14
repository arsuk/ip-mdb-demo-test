ip-mdb-test
===========

This is a simple class that generates pacs.008 messages to test Instant Payments. It
also consumes the pacs.002 responses.
It is an improvement on existing tools because it is a Java tool that will create
JMS messages with correct properties in the same way as real customers do. Furthermore,
the tool sends to and receives from multiple connections. There are therefore multiple
producers and consumers which is again what real clients do.
While the command is designed to send pacs.008 messages to an
instantpayments_mybank_originator_payment_request queue and receive pacs.002 messages
from an instantpayments_mybank_originator_payment_response queue it can send any text
message types, simply use another template and define other queues.
It is a JMS tool and can run with ActiveMQ or Artemis depending on the class definitions in the
jndi.properties in the current directory or an alternative file specified with -properties.

The command line is:

java -cp ip-mdb-test-1.2.jar Worldline.IPTestCommand
 
The can be displayed with -h and are:

Usage: <count> <tps> -template templatefile -values amounts -creditor-bics bics -debtor-bics bics -properties jndiprops
    -tps-range n -creditor-ibans ibans -debtor-ibans ibans -stop-status status -stop-count n -duplicate-check
    -receive-after-send
      
Defaults are: count=10 tps=1 template=pacs.008.xml
Change the jndi.properties file to change the broker hostname or queue names.
'-h' or '-?' gives this help info.
The values and bics/ibans parameters are used to replace the template elements for testing purposes.
These parameters can be comma separated lists, elements are selected randomly. If a filename is given
instead of a list then the file should have one element one per line.
The tps-range argument can be used to vary the tps rate within a second for + or - of the given range.
If count is zero then the tool will not send messages and will receive messages until the queue is empty (-1 forever).
Stop status is used to check the response status and stop the test if it matches after a stop count (default 1).
The stop status is a comma seperated string for status and reason, e.g. 'ACCP' or 'RJCT,AB05', regex syntax supported.
-duplicate-check will display a warning if the last 10000 responses include a duplicate TXID for the current message.
If -receive-after-send is present and there is a response queue then the command will wait for responses indefinitely.

The properties file can be used to set the number of connections used as well as queue names and the JMS factory that 
is to be used.
If no response queue is defined then the tool can only send messages. Queue names and connection info are defined
in the jndi properties file. An example is provided.

An advanced usage is to update the properties file at runtime. The tool will check if the tps, count, delay or restart
properties have been changed. Note that changing tps, count or delay only have effect when sending messages. Also, the
delay property overides the automatic calculation of the interval between sending messages for a session so it will
have an impact on tps and distribution. The restart property is 0 by default, it is the number of restarts the session
can have after unexpected termination (-1 means always restart).
