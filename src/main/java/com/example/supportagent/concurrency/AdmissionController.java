package com.example.supportagent.concurrency;

import com.example.supportagent.security.RequestIdentity;

public interface AdmissionController {
    Lease acquire(RequestIdentity identity);
}

