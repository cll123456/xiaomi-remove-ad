package dev.hyperadguard.system;

interface IPrivilegedService {
    String execute(in String[] command);
    byte[] captureScreenshot();
    void destroy();
}
