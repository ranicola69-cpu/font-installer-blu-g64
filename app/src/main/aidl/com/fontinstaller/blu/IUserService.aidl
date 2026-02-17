// IUserService.aidl
package com.fontinstaller.blu;

interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    String executeCommand(String command) = 2;
}
