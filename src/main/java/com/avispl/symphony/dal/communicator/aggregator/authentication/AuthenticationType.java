/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.aggregator.authentication;

/**
 * Enum of authentication types, available for Zoom Rooms aggregator.
 * JWT is used by default, but is going to become obsolete in 2023
 *
 * @author Maksym.Rossiytsev
 * @since 1.1.0
 * */
public enum AuthenticationType {
    OAuth, JWT
}
