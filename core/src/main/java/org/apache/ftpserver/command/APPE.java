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
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;

import org.apache.ftpserver.DefaultFtpReply;
import org.apache.ftpserver.IODataConnectionFactory;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.DataConnectionFactory;
import org.apache.ftpserver.ftplet.FileObject;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.Ftplet;
import org.apache.ftpserver.ftplet.FtpletEnum;
import org.apache.ftpserver.interfaces.FtpIoSession;
import org.apache.ftpserver.interfaces.FtpServerContext;
import org.apache.ftpserver.interfaces.ServerFtpStatistics;
import org.apache.ftpserver.util.FtpReplyUtil;
import org.apache.ftpserver.util.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>APPE &lt;SP&gt; &lt;pathname&gt; &lt;CRLF&gt;</code><br>
 *
 * This command causes the server-DTP to accept the data
 * transferred via the data connection and to store the data in
 * a file at the server site.  If the file specified in the
 * pathname exists at the server site, then the data shall be
 * appended to that file; otherwise the file specified in the
 * pathname shall be created at the server site.
 */
public 
class APPE extends AbstractCommand {
    
    private final Logger LOG = LoggerFactory.getLogger(APPE.class);
    
    /**
     * Execute command.
     */
    public void execute(FtpIoSession session,
                        FtpServerContext context, 
                        FtpRequest request) throws IOException, FtpException {
        
        try {
        
            // reset state variables
            session.resetState();
            
            // argument check
            String fileName = request.getArgument();
            if(fileName == null) {
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_501_SYNTAX_ERROR_IN_PARAMETERS_OR_ARGUMENTS, "APPE", null));
                return;  
            }
            
            // 24-10-2007 - added check if PORT or PASV is issued, see https://issues.apache.org/jira/browse/FTPSERVER-110
            DataConnectionFactory connFactory = session.getDataConnection();
            if (connFactory instanceof IODataConnectionFactory) {
                InetAddress address = ((IODataConnectionFactory)connFactory).getInetAddress();
                if (address == null) {
                	session.write(new DefaultFtpReply(FtpReply.REPLY_503_BAD_SEQUENCE_OF_COMMANDS, "PORT or PASV must be issued first"));
                    return;
                }
            }
            
            // call Ftplet.onAppendStart() method
            Ftplet ftpletContainer = context.getFtpletContainer();
            FtpletEnum ftpletRet;
            try {
                ftpletRet = ftpletContainer.onAppendStart(session.getFtpletSession(), request);
            } catch(Exception e) {
                LOG.debug("Ftplet container threw exception", e);
                ftpletRet = FtpletEnum.RET_DISCONNECT;
            }
            if(ftpletRet == FtpletEnum.RET_SKIP) {
                return;
            }
            else if(ftpletRet == FtpletEnum.RET_DISCONNECT) {
                session.closeOnFlush().awaitUninterruptibly(10000);
                return;
            }
            
            // get filenames
            FileObject file = null;
            try {
                file = session.getFileSystemView().getFileObject(fileName);
            }
            catch(Exception e) {
                LOG.debug("File system threw exception", e);
            }
            if(file == null) {
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN, "APPE.invalid", fileName));
                return;
            }
            fileName = file.getFullName();
            
            // check file existance
            if(file.doesExist() && !file.isFile()) {
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN, "APPE.invalid", fileName));
                return;
            }
            
            // check permission
            if( !file.hasWritePermission()) {
            	session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN, "APPE.permission", fileName));
                return;
            }
            
            // get data connection
            session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_150_FILE_STATUS_OKAY, "APPE", fileName));
            
            DataConnection dataConnection;
            try {
                dataConnection = session.getDataConnection().openConnection();
            } catch (Exception e) {
                LOG.debug("Exception when getting data input stream", e);
                session.write(FtpReplyUtil.translate(session,request,  context, FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "APPE", fileName));
                return;
            }
             
            // get data from client
            boolean failure = false;
            OutputStream os = null;
            try {
                
            	// find offset
            	long offset = 0L;
            	if(file.doesExist()) {
            		offset = file.getSize();
            	}
            	
                // open streams
                os = file.createOutputStream(offset);
                    
                // transfer data
                long transSz = dataConnection.transferFromClient(os);
                
                // log message
                String userName = session.getUser().getName();

                LOG.info("File upload : " + userName + " - " + fileName);
                
                // notify the statistics component
                ServerFtpStatistics ftpStat = (ServerFtpStatistics)context.getFtpStatistics();
                ftpStat.setUpload(session, file, transSz);
            }
            catch(SocketException e) {
                LOG.debug("SocketException during file upload", e);
                failure = true;
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED, "APPE", fileName));
            }
            catch(IOException e) {
                LOG.debug("IOException during file upload", e);
                failure = true;
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN, "APPE", fileName));
            }
            finally {
                IoUtils.close(os);
            }
            
            // if data transfer ok - send transfer complete message
            if(!failure) {
                session.write(FtpReplyUtil.translate(session, request, context, FtpReply.REPLY_226_CLOSING_DATA_CONNECTION, "APPE", fileName));
                
                // call Ftplet.onAppendEnd() method
                try {
                    ftpletRet = ftpletContainer.onAppendEnd(session.getFtpletSession(), request);
                } catch(Exception e) {
                    LOG.debug("Ftplet container threw exception", e);
                    ftpletRet = FtpletEnum.RET_DISCONNECT;
                }
                if(ftpletRet == FtpletEnum.RET_DISCONNECT) {
                    session.closeOnFlush().awaitUninterruptibly(10000);
                    return;
                }

            }
        }
        finally {
            session.getDataConnection().closeDataConnection();
        }
    }
}