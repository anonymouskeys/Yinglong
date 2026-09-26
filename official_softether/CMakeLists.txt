cmake_minimum_required(VERSION 3.22.1)
project(YinglongOfficialSoftEther C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(SE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/official/SoftEtherVPN")
set(SE_SRC  "${SE_ROOT}/src")

include("${CMAKE_CURRENT_SOURCE_DIR}/tools.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/ncurses.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/readline.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/iconv.cmake")

set(CEDAR_SRCS
    Account.c Admin.c AzureClient.c AzureServer.c Bridge.c BridgeUnix.c
    CM.c Cedar.c CedarPch.c Client.c Command.c Connection.c Console.c DDNS.c
    Database.c EM.c EtherLog.c Hub.c Layer3.c Link.c Listener.c Logging.c
    NM.c Nat.c NativeStack.c NullLan.c IPsec.c IPsec_EtherIP.c IPsec_IKE.c
    IPsec_IPC.c IPsec_IkePacket.c IPsec_L2TP.c IPsec_PPP.c Interop_OpenVPN.c
    Interop_SSTP.c Protocol.c Radius.c Remote.c SM.c SW.c Sam.c SeLowUser.c SecureInfo.c
    SecureNAT.c Server.c Session.c UT.c UdpAccel.c VG.c VLan.c VLanUnix.c
    Virtual.c WaterMark.c WebUI.c WinUi.c Wpc.c
)
PREPEND(CEDAR_SRC_PATHS "${SE_SRC}/Cedar" ${CEDAR_SRCS})

set(MAYAQUA_SRCS
    Cfg.c Encrypt.c FileIO.c Internat.c Kernel.c Mayaqua.c Memory.c Network.c
    OS.c Object.c Pack.c Secure.c Str.c Table.c TcpIp.c Tick64.c Tracking.c Unix.c
)
PREPEND(MAYAQUA_SRC_PATHS "${SE_SRC}/Mayaqua" ${MAYAQUA_SRCS})

add_library(cedar_official STATIC ${CEDAR_SRC_PATHS})
add_library(mayaqua_official STATIC ${MAYAQUA_SRC_PATHS})

target_include_directories(cedar_official PUBLIC
    "${SE_SRC}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/iconv/include"
)
target_include_directories(mayaqua_official PUBLIC
    "${SE_SRC}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/iconv/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/openssl/include"
)

target_compile_definitions(cedar_official PRIVATE
    UNIX UNIX_LINUX
    CEDAR_VERSION_MAJOR=4
    CEDAR_VERSION_MINOR=44
    CEDAR_VERSION_BUILD=9807
    BUILDER_NAME="Yinglong-Android"
    BUILD_PLACE="Android-NDK"
)
target_compile_definitions(mayaqua_official PRIVATE
    UNIX UNIX_LINUX SKIP_CPU_FEATURES
)

target_compile_options(cedar_official PRIVATE
    -fPIC -fsigned-char -O2
    -Wno-deprecated-declarations -Wno-unused-parameter -Wno-unused-variable
)
target_compile_options(mayaqua_official PRIVATE
    -fPIC -fsigned-char -O2
    -Wno-deprecated-declarations -Wno-unused-parameter -Wno-unused-variable
)

set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_library(ssl_prebuilt STATIC IMPORTED)
set_target_properties(ssl_prebuilt PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libssl.a")
add_library(crypto_prebuilt STATIC IMPORTED)
set_target_properties(crypto_prebuilt PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libcrypto.a")

add_library(softether SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/official_android/android_official_jni.c"
)

target_include_directories(softether PRIVATE
    "${SE_SRC}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/iconv/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/openssl/include"
)

target_link_libraries(softether PRIVATE
    "-Wl,--start-group"
    cedar_official
    mayaqua_official
    readline
    ncurses
    iconv
    ssl_prebuilt
    crypto_prebuilt
    z
    "-Wl,--end-group"
    log android dl m
)

target_link_options(softether PRIVATE "-Wl,--exclude-libs,ALL")

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/softether-core/src/proto/softether_protocol.c")
    message(STATUS "Legacy independent core exists in checkout but is NOT compiled.")
endif()
