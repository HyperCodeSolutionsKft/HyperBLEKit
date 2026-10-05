package io.hypercode.hyperblekit

/** The operating system of a phone that hosts a discovered game ([DiscoveredGame.platform]). */
public enum class Platform {
    /** An Android phone. */
    ANDROID,

    /** An iPhone or iPad. */
    IOS,

    /** Not known. An advertisement does not say which system sent it, so discovery reports this. */
    UNKNOWN;

    internal companion object {
        /** The platform this build runs on. */
        val THIS_DEVICE = ANDROID
    }
}
