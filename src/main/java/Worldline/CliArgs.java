package Worldline;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.lang.reflect.Field;

/*
    ArgsCli class
    Utility class for parsing command line arguments.
    
    Constructors support cli parsing without validation of switch names, validation with a list of valid names and
    validation using a POJO which also returns a POJO updated with parsed values.
    
    If the POJO is not used then switch methods should be called first to get their values before calling
    the target methods so that switch parameters can be excluded from the targets (positional parameters).
*/

public class CliArgs {
	
	public static String version = "CliArgs 1.1";

	private String[] args = null;

	private HashMap<String, Integer> switchIndexes = new HashMap<String, Integer>();  // Switches parsed from args
	private TreeSet<Integer>         takenIndexes  = new TreeSet<Integer>();  // arg indexes used for switch values
	private TreeSet<String>          switchNames = new TreeSet<String>(); // Switch names which are allowed on the command line
	private List<String>			 claimedSwitches = new ArrayList<String>(); //
	//private String[] targetArray=new String[0];

    /*
        Constructors
    */	
	public CliArgs(String[] args){
		// Parse without switch name list (if list empty there is no check)
		parse(args);
	}

	public CliArgs(String[] args, String[]switchList){
		// create list of allowed switch names - switchPojo adds its own names to the list and re-parses
		for (String swName: switchList) {
			switchNames.add(swName);
		}
		parse(args);
	}

	public void parse(String[] arguments){
		this.args = arguments;
		//locate argument switches.
		switchIndexes.clear();
		for(int i=0; i < args.length; i++) {
			if(isSwitch(args[i])) {
				// Check if switch in the pre-defined list if one has been created
				if (switchNames.size()>0&&!switchNames.contains(args[i])) {
					throw new RuntimeException("Invalid switch "+args[i]);
				}
				// Add switch to argument list switch list
				switchIndexes.put(args[i], i);
				takenIndexes.add(i);
			}
		}
	}

    /*
        Switch methods to get switch names (starting with '-') from the cli argument array as parsed by
        the constructors and possibly re-parsed if a POJO is used.
    */ 
	public String[] args() {
		return args;
	}

	public String arg(int index){
		return args[index];
	}

	public boolean switchPresent(String switchName) {
		claimedSwitches.add(switchName);
		return switchIndexes.containsKey(switchName);
	}

	public String switchValue(String switchName) {
		return switchValue(switchName, null);
	}

	public String switchValue(String switchName, String defaultValue) {
		claimedSwitches.add(switchName);
		if(!switchIndexes.containsKey(switchName)) return defaultValue;

		int switchIndex = switchIndexes.get(switchName);
		if(switchIndex + 1 < args.length){
			takenIndexes.add(switchIndex +1);
			return args[switchIndex +1];
		}
		return defaultValue;
	}

	public Integer switchIntValue(String switchName) {
		return switchIntValue(switchName, null);
	}

	public Integer switchIntValue(String switchName, Integer defaultValue) {
		String switchValue = switchValue(switchName, null);

		if(switchValue == null) return defaultValue;
		return Integer.parseInt(switchValue);
	}

	public Long switchLongValue(String switchName) {
		return switchLongValue(switchName, null);
	}

	public Long switchLongValue(String switchName, Long defaultValue) {
		String switchValue = switchValue(switchName, null);

		if(switchValue == null) return defaultValue;
		return Long.parseLong(switchValue);
	}

	public Double switchDoubleValue(String switchName) {
		return switchDoubleValue(switchName, null);
	}

	public Double switchDoubleValue(String switchName, Double defaultValue) {
		String switchValue = switchValue(switchName, null);

		if(switchValue == null) return defaultValue;
		return Double.parseDouble(switchValue);
	}

	public String[] switchList(String switchName) {
		if(!switchIndexes.containsKey(switchName)) return new String[0];

		int switchIndex = switchIndexes.get(switchName);

		String[] values = null;    
		int nextArgIndex = switchIndex + 1;
		if (nextArgIndex < args.length && !isSwitch(args[nextArgIndex])){
			values=args[nextArgIndex].split(",");
		}
		return values;
	}

	public String[] switchValues(String switchName) {
		if(!switchIndexes.containsKey(switchName)) return new String[0];

		int switchIndex = switchIndexes.get(switchName);

		int nextArgIndex = switchIndex + 1;
		while(nextArgIndex < args.length && !isSwitch(args[nextArgIndex])){
			takenIndexes.add(nextArgIndex);
			nextArgIndex++;
		}

		String[] values = new String[nextArgIndex - switchIndex - 1];
		for(int j=0; j < values.length; j++){
			values[j] = args[switchIndex + j + 1];
		}
		return values;
	}

    // Check if any switches are unclaimed (not yet registered by above methods or in a pojo).
    // Returns name of first unclaimed argument for use with a error an error message.
	// Call this after all the expected switches have been queried by the caller so the list is complete.
    public String unclaimedSwitch() {
        for (String arg:args) {
            if (isSwitch(arg) && !claimedSwitches.contains(arg)) return arg;
        };
        return null;
    }

    public void unclaimedSwitchCheck (){
        String arg=unclaimedSwitch();
	    if (arg!=null) {
			throw new RuntimeException("Invalid switch "+arg);
		}
    }

    /*
        Create a new POJO from an existing POJO mapping public class variables to cli arguments
        Switches start with '_' and '_' characters in java names are mapped to '-' in the argument names.
        Public class names without a leading '_' character are mapped to argument indexes in order of declaration,
        excluding the arguments reserved for switches.  
    */
	public <T> T switchPojo(Class<T> pojoClass){
		try {
			T pojo = pojoClass.getDeclaredConstructor().newInstance();
			
			int targetIndex=0;

			Field[] fields = pojoClass.getFields();
			// Loop for switches
			for(Field field : fields) {
				Class<?>  fieldType = field.getType();
				String fieldName;
				
				if (field.getName().startsWith("_")) {
					// switch name
					fieldName = field.getName().replace('_', '-');
					switchNames.add(fieldName);
					
					if(fieldType.equals(Boolean.class) || fieldType.equals(boolean.class)){
						field.set(pojo, switchPresent(fieldName) );
					} else if(fieldType.equals(String.class)) {
						if(switchValue(fieldName) != null){
							field.set(pojo, switchValue(fieldName) );
						}
					} else if(fieldType.equals(Long.class)    || fieldType.equals(long.class) ){
						if(switchLongValue(fieldName) != null){
							field.set(pojo, switchLongValue(fieldName) );
						}
					} else if(fieldType.equals(Integer.class)    || fieldType.equals(int.class) ){
						if(switchLongValue(fieldName) != null){
							field.set(pojo, switchLongValue(fieldName).intValue() );
						}
					} else if(fieldType.equals(Short.class)    || fieldType.equals(short.class) ){
						if(switchLongValue(fieldName) != null){
							field.set(pojo, switchLongValue(fieldName).shortValue() );
						}
					} else if(fieldType.equals(Byte.class)    || fieldType.equals(byte.class) ){
						if(switchLongValue(fieldName) != null){
							field.set(pojo, switchLongValue(fieldName).byteValue() );
						}
					} else if(fieldType.equals(Double.class)  || fieldType.equals(double.class)) {
						if(switchDoubleValue(fieldName) != null){
							field.set(pojo, switchDoubleValue(fieldName) );
						}
					} else if(fieldType.equals(Float.class)  || fieldType.equals(float.class)) {
						if(switchDoubleValue(fieldName) != null){
							field.set(pojo, switchDoubleValue(fieldName).floatValue() );
						}
					} else if(fieldType.equals(String[].class)){
						String[] values = switchValues(fieldName);
						if(values.length != 0){
							field.set(pojo, values);
						}
					}
				}
			};
			// Now switches defined with argument parameters (if any) we can re-parse to check if the 
			// argument switches.
			parse(args);
			
			// Loop for targets in POJO
			for (Field field : fields) {
				Class<?>  fieldType = field.getType();
				
				if (!field.getName().startsWith("_")) {					
					// positional parameter - target
					
					if(fieldType.equals(String.class)){
						if(targetValue(targetIndex) != null){
							field.set(pojo, targetValue(targetIndex) );
						}
					} else if(fieldType.equals(Long.class)    || fieldType.equals(long.class) ){
						if(targetLongValue(targetIndex) != null){
							field.set(pojo, targetLongValue(targetIndex) );
						}
					} else if(fieldType.equals(Integer.class)    || fieldType.equals(int.class) ){
						if(targetLongValue(targetIndex) != null){
							field.set(pojo, targetLongValue(targetIndex).intValue() );
						}
					} else if(fieldType.equals(Short.class)    || fieldType.equals(short.class) ){
						if(targetLongValue(targetIndex) != null){
							field.set(pojo, targetLongValue(targetIndex).shortValue() );
						}
					} else if(fieldType.equals(Byte.class)    || fieldType.equals(byte.class) ){
						if(targetLongValue(targetIndex) != null){
							field.set(pojo, targetLongValue(targetIndex).byteValue() );
						}
					} else if(fieldType.equals(Double.class)  || fieldType.equals(double.class)) {
						if(targetDoubleValue(targetIndex) != null){
							field.set(pojo, targetDoubleValue(targetIndex) );
						}
					} else if(fieldType.equals(Float.class)  || fieldType.equals(float.class)) {
						if(targetDoubleValue(targetIndex) != null){
							field.set(pojo, targetDoubleValue(targetIndex).floatValue() );
						}
					} else if(fieldType.equals(String[].class)){
						// All all positional parameters to the array - do not increment the index;
						targetIndex--;
						String[] values = targets();
						if(values.length != 0){
							field.set(pojo, values);
						}
					}
					targetIndex++;
				}
			}
			return pojo;
		} catch (Exception e) {
			throw new RuntimeException("Error creating switch POJO", e);
		}
	}

    /* 
        Query methods for targets (positional parameters)
        Targets entered via command line (POJO defaults not shown)
    */
	public String[] targets() {		
	    String[] targetArray = new String[args.length - takenIndexes.size()];
	    int targetIndex = 0;
	    for(int i = 0; i < args.length ; i++) {
	        if( !takenIndexes.contains(i) ) {
	            targetArray[targetIndex++] = args[i];
	        }
	    }
        return targetArray;
	}
	
	public String targetValue(int index) {		
		return targetValue(index,null);
	}
	
	public String targetValue(int index, String defaultValue) {
		String[] targetArray=targets();	// Lookup parameters allowing for switches
		if (index>=targetArray.length) return defaultValue;
		return targetArray[index];
	}
	
	public Integer targetIntValue(int index) {
		return targetIntValue(index, null);
	}

	public Integer targetIntValue(int index, Integer defaultValue) {
		String targetValue = targetValue(index);

		if(targetValue == null) return defaultValue;
		return Integer.parseInt(targetValue);
	}
	
	public Long targetLongValue(int index) {
		return targetLongValue(index, null);
	}

	public Long targetLongValue(int index, Long defaultValue) {
		String targetValue = targetValue(index);

		if(targetValue == null) return defaultValue;
		return Long.parseLong(targetValue);
	}

	public Double targetDoubleValue(int index) {
		return targetDoubleValue(index, null);
	}

	public Double targetDoubleValue(int index, Double defaultValue) {
		String targetValue = targetValue(index);

		if(targetValue == null) return defaultValue;
		return Double.parseDouble(targetValue);
	}

	public static boolean isSwitch(String str) {
		  return str.trim().startsWith("-") && !isNumeric(str);
	}
	
	public static boolean isNumeric(String str) {
		  return str.matches("-?\\d+(\\.\\d+)?");  //match a number with optional '-' and decimal.
	}
}
