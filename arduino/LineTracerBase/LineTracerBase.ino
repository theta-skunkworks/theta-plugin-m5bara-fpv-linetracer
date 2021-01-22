
/**
 * Copyright 2018 Ricoh Company, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <Wire.h>

#define M5GO_WHEEL_ADDR     0x56
#define MOTOR_CTRL_ADDR     0x00
#define ENCODER_ADDR        0x04

#define MOTOR_RPM           150
#define MAX_PWM             255
#define DEAD_ZONE           20

int motor0 = 0;
int motor1 = 0;

int timeout = 0;

String serialRead();
int splitParam2( String inStr, int *param1, int *param2 );
int splitParam3( String inStr, int *param1, int *param2, int *param3 );
void setMotor(int16_t pwm0, int16_t pwm1);
void stop();
void move_t(int16_t pwm0, int16_t pwm1, uint16_t duration, bool stopEna );


void setup() {
  Serial.begin(115200);     // SERIAL

  Wire.begin();
  Wire.setClock(400000UL);  // Set I2C frequency to 400kHz
  delay(500);

  setMotor( 0, 0 );  
}

void loop() {
  int speed=0;
  int delay_ms=0;
  String RcvCmd ="";

  RcvCmd = serialRead();
  RcvCmd.trim();
  if ( !(RcvCmd.equals("")) ) {
    //Serial.print("rcv=["+RcvCmd + "]\n");
    
    if ( RcvCmd.equals("go") ) {
      move_t( 80, 80, 1000, true );
      
    } else if ( RcvCmd.startsWith("set ") ) {
      RcvCmd.replace("set " , "");
      splitParam2( RcvCmd, &motor0, &motor1);
      setMotor( motor0, motor1 );
      timeout = 50; // およそ1秒

    } else if ( RcvCmd.startsWith("shake ") ) {
      RcvCmd.replace("shake " , "");
      splitParam2( RcvCmd, &speed, &delay_ms);
      move_t( 0, 0, 200, false );
      move_t( speed, speed, 200, true );
      move_t( -speed, -speed, delay_ms, true );
      move_t( 0, 0, 200, false );
      
    } else if ( RcvCmd.startsWith("mov_s ") ) {
      RcvCmd.replace("mov_s " , "");
      splitParam3( RcvCmd, &motor0, &motor1, &delay_ms);
      move_t( motor0, motor1, delay_ms, true );
      
    } else if ( RcvCmd.startsWith("mov_w ") ) {
      RcvCmd.replace("mov_w " , "");
      splitParam3( RcvCmd, &motor0, &motor1, &delay_ms);
      move_t( motor0, motor1, delay_ms, false );
      timeout = 50; // およそ1秒
      
    } else {
      stop();
    }

    //受信して動作したことを応答
    Serial.print("accept\n");
  }

  // 安全装置
  if ( timeout != 0 ) {
    timeout--;
    if ( timeout == 0 ) {
      stop();
    }
  }
  
  delay(20);
}


#define   SERIAL_BUFF_BYTE      512

String serialRead(){
  char  sSerialBuf[SERIAL_BUFF_BYTE];
  String result = "";

  if ( Serial.available() > 0 ) {
      int iPos=0;
      while (Serial.available()) {
        char c = Serial.read();
        if (  c == '\n' ) {
          break;
        } else {
          sSerialBuf[iPos] = c;
          iPos++;
          if (iPos==(SERIAL_BUFF_BYTE-1) ) {
            break;
          }
        }
      }
      sSerialBuf[iPos] = 0x00;
      result = String(sSerialBuf);
  }
  
  return result ;
}

int splitParam2( String inStr, int *param1, int *param2 ) {
  int ret = 0;
  
  inStr.trim();
  int len = inStr.length();
  int pos = inStr.indexOf(' ', 0);
  
  if ( (pos > 0) && (len>=3) ){
    String Param1 = inStr.substring(0, pos);
    String Param2 = inStr.substring(pos+1, len);
    //Serial.print("Param1=" + Param1 + ", Param2=" + Param2 +"\n");
    *param1 = Param1.toInt();
    *param2 = Param2.toInt();
  } else {
    ret = -1;
  }
  return ret;
}

int splitParam3( String inStr, int *param1, int *param2, int *param3 ) {
  int ret = 0;
  inStr.trim();
  int len = inStr.length();
  int pos = inStr.indexOf(' ', 0);
  int pos2 = inStr.indexOf(' ', pos+1);
  
  if ( (pos > 0) && (len>=5) ){
    String Param1 = inStr.substring(0, pos);
    String Param2 = inStr.substring(pos+1, pos2);
    String Param3 = inStr.substring(pos2+1, len);
    Serial.print("Param1=" + Param1 + ", Param2=" + Param2 + ", Param3=" + Param3 + "\n");
    *param1 = Param1.toInt();
    *param2 = Param2.toInt();
    *param3 = Param3.toInt();
  } else {
    ret = -1;
  }
    
  return ret;
}

void setMotor(int16_t pwm0, int16_t pwm1) {
  // Value range
  int16_t m0 = constrain(pwm0, -255, 255);
  int16_t m1 = constrain(pwm1, -255, 255);
  
  // Dead zone
  if (((m0 > 0) && (m0 < DEAD_ZONE)) || ((m0 < 0) && (m0 > -DEAD_ZONE))) m0 = 0;
  if (((m1 > 0) && (m1 < DEAD_ZONE)) || ((m1 < 0) && (m1 > -DEAD_ZONE))) m1 = 0;
  
  // Same value
  static int16_t pre_m0, pre_m1;
  if ((m0 == pre_m0) && (m1 == pre_m1))
    return;
  pre_m0 = m0;
  pre_m1 = m1;

  Wire.beginTransmission(M5GO_WHEEL_ADDR);
  Wire.write(MOTOR_CTRL_ADDR); // Motor ctrl reg addr
  Wire.write(((uint8_t*)&m0)[0]);
  Wire.write(((uint8_t*)&m0)[1]);
  Wire.write(((uint8_t*)&m1)[0]);
  Wire.write(((uint8_t*)&m1)[1]);
  Wire.endTransmission();
}

void stop(){
  motor0 = 0;
  motor1 = 0;
  setMotor( motor0, motor1 );
}

void move_t(int16_t pwm0, int16_t pwm1, uint16_t duration, bool stopEna){
  setMotor( pwm0, pwm1 );
  
  if (duration != 0) {
    delay(duration);
    if (stopEna) {
      stop();
    }
  }
}
