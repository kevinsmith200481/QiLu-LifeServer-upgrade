package com.qilu.acceptance;

public class AcceptanceInjectedFaultException extends RuntimeException {

    public AcceptanceInjectedFaultException(String faultCode) {
        super(faultCode);
    }
}
