package embs;
import com.ibm.saguaro.system.*;
import com.ibm.saguaro.logger.*;
public class MyUtils {

		/**
		 * Returns the absolute value of a number, i.e. the positive version of the number
		 * @param value
		 * @return
		 */
    	public static long abs(long value){
    		if(value == 0) {
    			return 0;
    		}
    		if(value < 0) {
    			return value * -1;
    		} else {
    			return value;
    		}
    	}
    	
         /**
          * All the frames are packed together and addressed with an offset and a length in the transmit method
          * @param channels_to_panid
          * @param short_to_panid
          * @param panid
          * @param address
          * @return
          */
         public static byte[] prepareFrame(byte[] channels_to_panid, byte[] short_to_panid, int panid, int address) {
     		 // Prepare beacon frame with source and destination addressing
             byte[] xmit = new byte[36];
             xmit[0] = Radio.FCF_BEACON;
             xmit[1] = Radio.FCA_SRC_SADDR|Radio.FCA_DST_SADDR;
             Util.set16le(xmit, 3, channels_to_panid[0]); // destination PAN address 
             Util.set16le(xmit, 5, short_to_panid[0]); 
             Util.set16le(xmit, 7, panid); // own PAN address 
             Util.set16le(xmit, 9, address); // own short address 
     		 xmit[11] = (byte)69;
     		
             xmit[12] = Radio.FCF_BEACON;
             xmit[13] = Radio.FCA_SRC_SADDR|Radio.FCA_DST_SADDR;
             Util.set16le(xmit, 15, channels_to_panid[1]); // destination PAN address 
             Util.set16le(xmit, 17, short_to_panid[1]); 
             Util.set16le(xmit, 19, panid); // own PAN addres
             Util.set16le(xmit, 21, address); // own short address 
     		 xmit[23] = (byte)69;
     	
             xmit[24] = Radio.FCF_BEACON;
             xmit[25] = Radio.FCA_SRC_SADDR|Radio.FCA_DST_SADDR;
             Util.set16le(xmit, 27, channels_to_panid[2]); // destination PAN address 
             Util.set16le(xmit, 29, short_to_panid[2]); 
             Util.set16le(xmit, 31, panid); // own PAN address 
             Util.set16le(xmit, 33, address); // own short address 
     		 xmit[35] = (byte)69;
     		 
    		 return xmit;
     	}
         
        /**
         * returns true if the value of T for the channel has been estimated
         * @param channel
         * @param channel_to_T
         * @return True if we know T
         */
        public static boolean knowT(int channel, long[] channel_to_T) {
     		return (channel_to_T[(int)channel] != 0L);
     	}
        
        /**
         * Sets up the three timer needed to send frames to the three sinks.
         * @param timers
         */
        public static void setUpChannelTimers(Timer[]  timers) {
    		timers[0] = new Timer();
    		timers[1] = new Timer();
    		timers[2] = new Timer();
    		// The parameter specifies channel 0
    		timers[0].setParam((byte)0);
    		timers[0].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time) {
                    Source.sendToChannel(param, time);
                }
            }); 
    		// The parameter specifies channel 1
    		timers[1].setParam((byte)1);
    		timers[1].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    Source.sendToChannel(param, time);
                }
            }); 
    		// The parameter specifies channel 2
    		timers[2].setParam((byte)2);
    		timers[2].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    Source.sendToChannel(param, time);
                }
            });
    	}
        
        /**
         * Sets up the three timer needed to switch to each channel.
         * @param channelSwitchers
         */
        public static void setUpWaitChannelSwitch(Timer[] channelSwitchers) {
        	
        	channelSwitchers[0] = new Timer();
        	// The parameter specifies channel 0
        	channelSwitchers[0].setParam((byte)0);
        	channelSwitchers[0].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    Source.switchToChannel(param, time);
                }
            });
        	channelSwitchers[1]= new Timer();
        	// The parameter specifies channel 1
        	channelSwitchers[1].setParam((byte)1);
        	channelSwitchers[1].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    Source.switchToChannel(param, time);
                }
            });
        	channelSwitchers[2] = new Timer();
        	// The parameter specifies channel 2
        	channelSwitchers[2].setParam((byte)2);
        	channelSwitchers[2].setCallback(new TimerEvent(null){
                public void invoke(byte param, long time){
                    Source.switchToChannel(param, time);
                }
            });
    	}
        
		/**
		 * Sets up the radio used by source
		 * @param radio
		 */
		public static void setUpRadio(Radio radio) {
			radio.open(Radio.DID, null, 0, 0);
			radio.setRxHandler(new DevCallback(null){
	            public int invoke (int flags, byte[] data, int len, int info, long time) {
	                return  Source.onReceive(flags, data, len, info, time);
	            }
	        });
		}
		/**
		 * Timer used to implement a delay between transmitting a frame to a channel and switching back to the old channel
		 * @param waitAfterTransmissionToChangeChannel
		 */
		public static void setUpWaitAfterTransmissionToChangeChannel(Timer waitAfterTransmissionToChangeChannel) {
			waitAfterTransmissionToChangeChannel = new Timer();
			waitAfterTransmissionToChangeChannel.setCallback(new TimerEvent(null){
	            public void invoke(byte param, long time){
	                Source.switchToOldChannel(param, time);
	            }
	        });
		}
		
		// Logger utilities
		
		public static void logTime(byte[] message){
	    	Logger.appendString(message);
	    	Logger.appendLong(Time.fromTickSpan(Time.MILLISECS, Time.currentTicks()));
	        Logger.flush(Mote.WARN);
	     }
     
	     public static void printStringNumber(byte[] message, long number) {
	     	Logger.appendString(message);
	     	Logger.appendLong(number);
	        Logger.flush(Mote.WARN);
	     }
		     
	     public static void print(byte[] message) {
	      	Logger.appendString(message);
	        Logger.flush(Mote.INFO);
	      }

    }