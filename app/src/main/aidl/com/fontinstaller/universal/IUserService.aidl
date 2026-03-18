package com.fontinstaller.universal;

interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    String executeCommand(String command) = 2;
    int getUid() = 3;
}
