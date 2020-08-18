import java.util.Scanner;

public class MyArgs {
	
	String[]argsArray;
	
	public MyArgs (String[] args) {
		argsArray=args;
	}
	
	public MyArgs () {
		throw new RuntimeException("Must supply arguments");
	}

	// Class methods
	
    public String getenv(String key, String defaultValue) {
    	return env(key,defaultValue);
    }
    
	public String getarg (int index) {
		return arg(argsArray,index);
    }
    
	public String getarg (int index, String defaultValue) {
		return arg(argsArray,index,defaultValue);
    } 

	public String getarg (String key, String defaultValue) {
		return arg(argsArray,key,defaultValue);
    } 
	
	public boolean getarg (String key) {
		return arg(argsArray,key);
    } 
	
	public void setargs (String[] args) {
		argsArray=args;
	}
	
	public String[] getargs () {
		return argsArray;
	}

	// Static methods
	
    public static String env(String key, String defaultValue) {
        String rc = System.getenv(key);
        if( rc==null )
            return defaultValue;
        return rc;
    }

    public static String arg(String []args, int index) {
        if( index < args.length)
                return args[index]; // return wanted argument
        else
        	return null;
    }

    public static String arg(String []args, int index, String defaultValue) {
        if( index < args.length && args[index].length()>0 )
            if (args[index].startsWith("-") && !new Scanner(args[index]).hasNextInt()) {
                return defaultValue; // Looks like a non-positional '-arg' so ignore
            }
            else {
                return args[index]; // return wanted argument
            }
        else
            return defaultValue;
    }

    public static String arg(String []args, String key, String defaultValue) {
        for(int i=0; i<args.length; i++ ) {
            if ( args[i].equals(key)) {
            	String ret=defaultValue;
            	if (i+1<args.length) ret=args[i+1];
            	return ret;
            }
        }
        return defaultValue;
    }   
    
    public static boolean arg(String []args, String key) {
        for(int i=0; i<args.length; i++ ) {
            if ( args[i].equals(key)) 
            	return true;
        }
        return false;
    }
    
    private static int toInt(String intval,String msg, Integer defval) {
    	// String to int conversion with error handling.
    	// Return the default value on error if there is one, otherwise exit. 
    	try{ 
    		return Integer.parseInt(intval);
    	} catch (Exception e) {
    		if (msg==null)
    			System.err.println(e+" value: "+intval);
    		else
    			System.out.println(msg+" "+intval);
   			if (defval==null) System.exit(1);
   			return defval.intValue();
    	}
    }
    public static int toInt(String intval,String msg,int def) {
    	return toInt(intval,msg,new Integer(def));
    }    
    public static int toInt(String intval,String msg) {
    	return toInt(intval,msg,null);
    }
    public static int toInt(String intval,int defaultval) {
    	return toInt(intval,null,defaultval);
    }
    public static int toInt(String intval) {
    	return toInt(intval,null,null);
    }

    public static boolean isNumeric(String str) {
        if (str == null)
            return false;
        return str.matches("-?\\d+");
    }
}
