use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use crate::{
    add_members, create_group, encrypt, epoch, generate_key_package, join_group, members,
    key_package_identity, open_database, process_message, provider_with_signer, remove_member, MlsError, OpenDatabase,
};
use jni::{
    objects::{JByteArray, JClass, JObjectArray, JString},
    sys::{jbyteArray, jlong, jstring},
    JNIEnv,
};
use serde::Serialize;
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::{Mutex, OnceLock},
};
use zeroize::Zeroizing;

struct NativeConfig {
    path: String,
    key: Zeroizing<Vec<u8>>,
}

static CONFIG: OnceLock<Mutex<Option<NativeConfig>>> = OnceLock::new();

fn config() -> &'static Mutex<Option<NativeConfig>> {
    CONFIG.get_or_init(|| Mutex::new(None))
}

fn with_database<T>(
    operation: impl FnOnce(&mut OpenDatabase) -> Result<T, MlsError>,
) -> Result<T, MlsError> {
    let guard = config().lock().map_err(|_| MlsError::Rejected)?;
    let cfg = guard.as_ref().ok_or(MlsError::Rejected)?;
    let mut database = open_database(&cfg.path, &cfg.key)?;
    operation(&mut database)
}

fn throw(env: &mut JNIEnv, error: impl std::fmt::Display) {
    let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
}

fn input_bytes(env: &mut JNIEnv, value: JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(&value).map_err(|e| e.to_string())
}

fn output_bytes(env: &mut JNIEnv, value: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(value) {
        Ok(array) => array.into_raw(),
        Err(error) => {
            throw(env, error);
            ptr::null_mut()
        }
    }
}

fn output_json<T: Serialize>(env: &mut JNIEnv, value: &T) -> jstring {
    match serde_json::to_string(value)
        .map_err(|e| e.to_string())
        .and_then(|json| env.new_string(json).map(|s| s.into_raw()).map_err(|e| e.to_string()))
    {
        Ok(value) => value,
        Err(error) => {
            throw(env, error);
            ptr::null_mut()
        }
    }
}

fn catch_result<T>(
    env: &mut JNIEnv,
    operation: impl FnOnce() -> Result<T, MlsError>,
) -> Option<T> {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(value)) => Some(value),
        Ok(Err(error)) => {
            throw(env, error);
            None
        }
        Err(_) => {
            throw(env, "native MLS engine failed safely");
            None
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    key: JByteArray,
) {
    let result = (|| -> Result<(), String> {
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        if path.is_empty() || path.len() > 4096 {
            return Err("invalid MLS database path".to_owned());
        }
        let key = input_bytes(&mut env, key)?;
        let mut database = open_database(&path, &key).map_err(|e| e.to_string())?;
        let (_provider, _signer) = provider_with_signer(&mut database).map_err(|e| e.to_string())?;
        let mut guard = config().lock().map_err(|_| "MLS configuration lock poisoned".to_owned())?;
        *guard = Some(NativeConfig { path, key: Zeroizing::new(key) });
        Ok(())
    })();

    if let Err(error) = result {
        throw(&mut env, error);
    }
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeCreateGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
    credential: JByteArray,
) -> jlong {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return -1; }
    };
    let credential = match input_bytes(&mut env, credential) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return -1; }
    };
    catch_result(&mut env, || with_database(|db| create_group(db, &group_id, &credential)))
        .map(|epoch| epoch as jlong)
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeGenerateKeyPackage(
    mut env: JNIEnv,
    _class: JClass,
    credential: JByteArray,
) -> jbyteArray {
    let credential = match input_bytes(&mut env, credential) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || with_database(|db| generate_key_package(db, &credential)));
    result.map(|value| output_bytes(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeKeyPackageIdentity(
    mut env: JNIEnv,
    _class: JClass,
    key_package: JByteArray,
) -> jbyteArray {
    let key_package = match input_bytes(&mut env, key_package) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || with_database(|db| key_package_identity(db, &key_package)));
    result.map(|value| output_bytes(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeAddMembers(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
    packages: JObjectArray,
) -> jstring {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let length = match env.get_array_length(&packages) {
        Ok(value) if (1..=200).contains(&value) => value,
        _ => { throw(&mut env, "invalid MLS KeyPackage count"); return ptr::null_mut(); }
    };
    let mut values = Vec::with_capacity(length as usize);
    for index in 0..length {
        let object = match env.get_object_array_element(&packages, index) {
            Ok(value) => value,
            Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
        };
        let bytes = match input_bytes(&mut env, JByteArray::from(object)) {
            Ok(value) => value,
            Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
        };
        values.push(bytes);
    }
    let result = catch_result(&mut env, || with_database(|db| add_members(db, &group_id, &values)));
    result.map(|value| output_json(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeJoinGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
    welcome: JByteArray,
) -> jlong {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return -1; }
    };
    let welcome = match input_bytes(&mut env, welcome) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return -1; }
    };
    catch_result(&mut env, || with_database(|db| join_group(db, &group_id, &welcome)))
        .map(|epoch| epoch as jlong)
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeEncrypt(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let plaintext = match input_bytes(&mut env, plaintext) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || with_database(|db| encrypt(db, &group_id, &plaintext)));
    result.map(|value| output_bytes(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeProcessMessage(
    mut env: JNIEnv,
    _class: JClass,
    message: JByteArray,
) -> jstring {
    let message = match input_bytes(&mut env, message) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || with_database(|db| process_message(db, &message)));
    result.map(|value| output_json(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeRemoveMember(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
    credential_identity: JByteArray,
) -> jstring {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let credential_identity = match input_bytes(&mut env, credential_identity) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || with_database(|db| remove_member(db, &group_id, &credential_identity)));
    result.map(|value| output_json(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeEpoch(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
) -> jlong {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return -1; }
    };
    catch_result(&mut env, || with_database(|db| epoch(db, &group_id)))
        .map(|epoch| epoch as jlong)
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeMembers(
    mut env: JNIEnv,
    _class: JClass,
    group_id: JByteArray,
) -> jstring {
    let group_id = match input_bytes(&mut env, group_id) {
        Ok(value) => value,
        Err(error) => { throw(&mut env, error); return ptr::null_mut(); }
    };
    let result = catch_result(&mut env, || {
        with_database(|db| {
            members(db, &group_id).map(|values| {
                values.into_iter().map(|value| BASE64.encode(value)).collect::<Vec<_>>()
            })
        })
    });
    result.map(|value| output_json(&mut env, &value)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_relyra_app_core_messaging_MlsNative_nativeClose(
    mut env: JNIEnv,
    _class: JClass,
) {
    match config().lock() {
        Ok(mut value) => *value = None,
        Err(_) => throw(&mut env, "MLS configuration lock poisoned"),
    }
}
