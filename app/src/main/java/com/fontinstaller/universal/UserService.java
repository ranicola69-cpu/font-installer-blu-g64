package com.fontinstaller.universal;

import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class UserService extends IUserService.Stub {
    
    public UserService() {
    }

    @Override
    public void destroy() throws RemoteException {
        System.exit(0);
    }

    @Override
    public void exit() throws RemoteException {
        destroy();
    }

    @Override
    public String executeCommand(String command) throws RemoteException {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = stdInput.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = stdError.readLine()) != null) {
                output.append("ERR: ").append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            return "EXIT:" + exitCode + "\n" + output.toString();
        } catch (Exception e) {
            return "EXCEPTION: " + e.getMessage();
        }
    }

    @Override
    public int getUid() throws RemoteException {
        return android.os.Process.myUid();
    }
}
