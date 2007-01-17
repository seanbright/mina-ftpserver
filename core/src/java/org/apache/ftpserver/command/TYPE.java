/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */  

package org.apache.ftpserver.command;

import java.io.IOException;

import org.apache.ftpserver.FtpSessionImpl;
import org.apache.ftpserver.FtpWriter;
import org.apache.ftpserver.ftplet.DataType;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpResponse;
import org.apache.ftpserver.listener.Connection;

/**
 * <code>TYPE &lt;SP&gt; &lt;type-code&gt; &lt;CRLF&gt;</code><br>
 *
 * The argument specifies the representation type.
 * 
 * @author <a href="mailto:rana_b@yahoo.com">Rana Bhattacharyya</a>
 */
public 
class TYPE extends AbstractCommand {
    
    /**
     * Execute command
     */
    public void execute(Connection connection,
                        FtpRequest request,
                        FtpSessionImpl session, 
                        FtpWriter out) throws IOException {
        
        // reset state variables
        session.resetState();
        
        // get type from argument
        char type = 'A';
        if (request.hasArgument()){
            type = request.getArgument().charAt(0);
        }
        
        // set type
        try {
            session.setDataType(DataType.parseArgument(type));
            out.send(FtpResponse.REPLY_200_COMMAND_OKAY, "TYPE", null);
        } 
        catch(IllegalArgumentException e) {
            log.debug("Illegal type argument: " + request.getArgument(), e);
            out.send(FtpResponse.REPLY_504_COMMAND_NOT_IMPLEMENTED_FOR_THAT_PARAMETER, "TYPE", null);
        }
    }
    
}
