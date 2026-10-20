# Paho MQTTv5 looks its logger up by class name; without this R8 removes it and connect() throws
# MissingResourceException ("Error locating the logging class"). Found on the X1 and X2i, spike S4.
-keep class org.eclipse.paho.mqttv5.client.logging.JSR47Logger { <init>(); }
