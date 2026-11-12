//! RELYRA's native MLS engine.
//!
//! OpenMLS 0.9.2 is pinned deliberately. All OpenMLS storage is backed by a
//! SQLCipher database keyed with the same Keystore-derived database key used
//! by the Android app. The key is supplied for each process initialization and
//! is never written to disk by this crate.

mod jni;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use openmls::{
    framing::ProcessedMessageContent,
    prelude::*,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_sqlite_storage::{Codec, Connection, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use rusqlite::OptionalExtension;
use serde::{Deserialize, Serialize};
use std::path::Path;
use tls_codec::{Deserialize as TlsDeserialize, Serialize as TlsSerialize};
use zeroize::Zeroizing;

pub const MLS_CIPHERSUITE: Ciphersuite =
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

#[derive(Default)]
pub struct JsonCodec;

impl Codec for JsonCodec {
    type Error = serde_json::Error;

    fn to_vec<T: Serialize>(value: &T) -> Result<Vec<u8>, Self::Error> {
        serde_json::to_vec(value)
    }

    fn from_slice<T: for<'de> Deserialize<'de>>(slice: &[u8]) -> Result<T, Self::Error> {
        serde_json::from_slice(slice)
    }
}

/// OpenMLS provider using the reviewed RustCrypto primitives and a SQLCipher
/// storage provider. OpenMLS state and long-lived signature keys are persisted
/// in the encrypted database, not in process memory.
pub struct SqlCipherMlsProvider<'a> {
    crypto: OpenMlsRustCrypto,
    storage: SqliteStorageProvider<JsonCodec, &'a mut Connection>,
}

impl<'a> SqlCipherMlsProvider<'a> {
    pub fn new(connection: &'a mut Connection) -> Result<Self, MlsError> {
        let mut storage = SqliteStorageProvider::<JsonCodec, _>::new(connection);
        storage.run_migrations().map_err(|e| MlsError::Storage(e.to_string()))?;
        Ok(Self {
            crypto: OpenMlsRustCrypto::default(),
            storage,
        })
    }
}

impl<'a> OpenMlsProvider for SqlCipherMlsProvider<'a> {
    type CryptoProvider = <OpenMlsRustCrypto as OpenMlsProvider>::CryptoProvider;
    type RandProvider = <OpenMlsRustCrypto as OpenMlsProvider>::RandProvider;
    type StorageProvider = SqliteStorageProvider<JsonCodec, &'a mut Connection>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.crypto.crypto()
    }

    fn rand(&self) -> &Self::RandProvider {
        self.crypto.rand()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum MlsError {
    #[error("invalid SQLCipher database key")]
    InvalidDatabaseKey,
    #[error("could not access MLS storage: {0}")]
    Storage(String),
    #[error("SQLite failure: {0}")]
    Sql(rusqlite::Error),
    #[error("MLS cryptographic operation failed")]
    Crypto,
    #[error("the local MLS signing key is missing")]
    MissingSigningKey,
    #[error("group does not exist")]
    GroupNotFound,
    #[error("group is not active")]
    Inactive,
    #[error("invalid MLS message")]
    InvalidMessage,
    #[error("MLS operation rejected")]
    Rejected,
    #[error("invalid key package")]
    InvalidKeyPackage,
    #[error("credential identity does not match the requested RELYRA identity")]
    IdentityMismatch,
    #[error("invalid group identity")]
    InvalidGroupId,
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("serialization error: {0}")]
    Json(#[from] serde_json::Error),
}

pub struct OpenDatabase {
    pub connection: Connection,
    _key: Zeroizing<Vec<u8>>,
}

pub fn open_database(path: &str, key: &[u8]) -> Result<OpenDatabase, MlsError> {
    if key.len() < 32 || key.len() > 64 || path.is_empty() || path.len() > 4096 {
        return Err(MlsError::InvalidDatabaseKey);
    }
    if let Some(parent) = Path::new(path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    let key = Zeroizing::new(key.to_vec());
    let mut connection = Connection::open(path).map_err(MlsError::Sql)?;
    let hex_key = key.iter().map(|b| format!("{b:02x}")).collect::<String>();
    connection.execute_batch(&format!("PRAGMA key = \"x'{hex_key}'\";")).map_err(MlsError::Sql)?;
    connection.query_row("SELECT count(*) FROM sqlite_master", [], |row| row.get::<_, i64>(0))
        .map_err(MlsError::Sql)?;
    connection.execute_batch(
        "PRAGMA foreign_keys = ON;
         PRAGMA journal_mode = WAL;
         PRAGMA secure_delete = ON;
         CREATE TABLE IF NOT EXISTS relyra_mls_meta (
             slot INTEGER PRIMARY KEY CHECK(slot = 1),
             public_key BLOB NOT NULL,
             key_pair BLOB NOT NULL
         );"
    ).map_err(MlsError::Sql)?;
    Ok(OpenDatabase { connection, _key: key })
}

fn provider_with_signer<'a>(
    database: &'a mut OpenDatabase,
) -> Result<(SqlCipherMlsProvider<'a>, SignatureKeyPair), MlsError> {
    let stored: Option<(Vec<u8>, Vec<u8>)> = database.connection.query_row(
        "SELECT public_key, key_pair FROM relyra_mls_meta WHERE slot = 1",
        [],
        |row| Ok((row.get(0)?, row.get(1)?)),
    ).optional().map_err(MlsError::Sql)?;

    let signer = if let Some((public_key, serialized)) = stored {
        let pair = SignatureKeyPair::tls_deserialize_exact(serialized.as_slice())
            .map_err(|_| MlsError::MissingSigningKey)?;
        if pair.public() != public_key.as_slice() {
            return Err(MlsError::MissingSigningKey);
        }
        pair
    } else {
        let pair = SignatureKeyPair::new(SignatureScheme::ED25519)
            .map_err(|_| MlsError::Crypto)?;
        let serialized = pair.tls_serialize_detached()
            .map_err(|_| MlsError::Crypto)?;
        database.connection.execute(
            "INSERT INTO relyra_mls_meta(slot, public_key, key_pair) VALUES(1, ?1, ?2)",
            rusqlite::params![pair.public(), serialized],
        ).map_err(MlsError::Sql)?;
        pair
    };

    let provider = SqlCipherMlsProvider::new(&mut database.connection)?;
    signer.store(provider.storage()).map_err(|e| MlsError::Storage(e.to_string()))?;
    Ok((provider, signer))
}

pub fn create_group(
    database: &mut OpenDatabase,
    group_id: &[u8],
    credential_identity: &[u8],
) -> Result<u64, MlsError> {
    if group_id.is_empty() || group_id.len() > 128 || credential_identity.is_empty() || credential_identity.len() > 2048 {
        return Err(MlsError::InvalidGroupId);
    }
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    if let Some(existing) = MlsGroup::load(provider.storage(), &id).map_err(|e| MlsError::Storage(e.to_string()))? {
        return Ok(existing.epoch().as_u64());
    }
    let config = MlsGroupCreateConfig::builder()
        .ciphersuite(MLS_CIPHERSUITE)
        .use_ratchet_tree_extension(true)
        .build();
    let credential = BasicCredential::new(credential_identity.to_vec());
    let _group = MlsGroup::new_with_group_id(
        &provider,
        &signer,
        &config,
        id,
        CredentialWithKey {
            credential: credential.into(),
            signature_key: signer.public().into(),
        },
    ).map_err(|_| MlsError::Rejected)?;
    Ok(0)
}

pub fn generate_key_package(
    database: &mut OpenDatabase,
    credential_identity: &[u8],
) -> Result<Vec<u8>, MlsError> {
    if credential_identity.is_empty() || credential_identity.len() > 2048 {
        return Err(MlsError::IdentityMismatch);
    }
    let (provider, signer) = provider_with_signer(database)?;
    let credential = BasicCredential::new(credential_identity.to_vec());
    let bundle = KeyPackage::builder()
        .build(
            MLS_CIPHERSUITE,
            &provider,
            &signer,
            CredentialWithKey {
                credential: credential.into(),
                signature_key: signer.public().into(),
            },
        )
        .map_err(|_| MlsError::InvalidKeyPackage)?;
    bundle.key_package().tls_serialize_detached().map_err(|_| MlsError::InvalidKeyPackage)
}

#[derive(Serialize)]
pub struct CommitBundle {
    pub commit: String,
    pub welcome: Option<String>,
    pub epoch: u64,
}

pub fn key_package_identity(
    database: &mut OpenDatabase,
    key_package: &[u8],
) -> Result<Vec<u8>, MlsError> {
    if key_package.is_empty() || key_package.len() > 64 * 1024 {
        return Err(MlsError::InvalidKeyPackage);
    }
    let (provider, _signer) = provider_with_signer(database)?;
    let input = KeyPackageIn::tls_deserialize_exact_bytes(key_package)
        .map_err(|_| MlsError::InvalidKeyPackage)?;
    let identity = input.unverified_credential().credential.serialized_content().to_vec();
    input.validate(provider.crypto(), ProtocolVersion::Mls10)
        .map_err(|_| MlsError::InvalidKeyPackage)?;
    if identity.is_empty() || identity.len() > 2048 {
        return Err(MlsError::IdentityMismatch);
    }
    Ok(identity)
}

pub fn add_members(
    database: &mut OpenDatabase,
    group_id: &[u8],
    key_packages: &[Vec<u8>],
) -> Result<CommitBundle, MlsError> {
    if key_packages.is_empty() || key_packages.len() > 200 {
        return Err(MlsError::InvalidKeyPackage);
    }
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    let packages = key_packages.iter().map(|bytes| {
        let input = KeyPackageIn::tls_deserialize_exact_bytes(bytes.as_slice())
            .map_err(|_| MlsError::InvalidKeyPackage)?;
        input.validate(provider.crypto(), ProtocolVersion::Mls10).map_err(|_| MlsError::InvalidKeyPackage)
    }).collect::<Result<Vec<_>, _>>()?;
    let (commit, welcome, _group_info) = group.add_members(&provider, &signer, &packages)
        .map_err(|_| MlsError::Rejected)?;
    group.merge_pending_commit(&provider).map_err(|_| MlsError::Rejected)?;
    let commit = commit.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)?;
    let welcome = welcome.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)?;
    Ok(CommitBundle {
        commit: BASE64.encode(commit),
        welcome: Some(BASE64.encode(welcome)),
        epoch: group.epoch().as_u64(),
    })
}

pub fn join_group(
    database: &mut OpenDatabase,
    group_id: &[u8],
    welcome_bytes: &[u8],
    expected_identity: &[u8],
) -> Result<u64, MlsError> {
    let (provider, _signer) = provider_with_signer(database)?;
    let welcome_message = MlsMessageIn::tls_deserialize_exact_bytes(welcome_bytes)
        .map_err(|_| MlsError::InvalidMessage)?;
    let welcome = match welcome_message.extract() {
        MlsMessageBodyIn::Welcome(welcome) => welcome,
        _ => return Err(MlsError::InvalidMessage),
    };
    let staged = StagedWelcome::new_from_welcome(
        &provider,
        &MlsGroupJoinConfig::default(),
        welcome,
        None,
    ).map_err(|_| MlsError::InvalidMessage)?;
    let group = staged.into_group(&provider).map_err(|_| MlsError::Rejected)?;
    if group.group_id().as_slice() != group_id ||
        !group.members().any(|member| member.credential.serialized_content() == expected_identity) {
        return Err(MlsError::IdentityMismatch);
    }
    Ok(group.epoch().as_u64())
}

pub fn encrypt(
    database: &mut OpenDatabase,
    group_id: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, MlsError> {
    if plaintext.is_empty() || plaintext.len() > 1024 * 1024 {
        return Err(MlsError::Rejected);
    }
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    if !group.is_active() {
        return Err(MlsError::Inactive);
    }
    let message = group.create_message(&provider, &signer, plaintext)
        .map_err(|_| MlsError::Rejected)?;
    message.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)
}

#[derive(Serialize)]
pub struct ProcessResult {
    pub kind: String,
    pub group_id: String,
    pub epoch: u64,
    pub application: Option<String>,
}

pub fn process_message(
    database: &mut OpenDatabase,
    message: &[u8],
) -> Result<ProcessResult, MlsError> {
    if message.is_empty() || message.len() > 1024 * 1024 {
        return Err(MlsError::InvalidMessage);
    }
    let (provider, _signer) = provider_with_signer(database)?;
    let incoming = MlsMessageIn::tls_deserialize_exact_bytes(message)
        .map_err(|_| MlsError::InvalidMessage)?;
    let protocol: ProtocolMessage = incoming.try_into_protocol_message()
        .map_err(|_| MlsError::InvalidMessage)?;
    let id_bytes = protocol.group_id().as_slice().to_vec();
    let id = GroupId::from_slice(&id_bytes);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    if !group.is_active() {
        return Err(MlsError::Inactive);
    }
    let processed = group.process_message(&provider, protocol)
        .map_err(|_| MlsError::InvalidMessage)?;
    let (kind, application) = match processed.into_content() {
        ProcessedMessageContent::ApplicationMessage(application) => {
            ("application".to_owned(), Some(BASE64.encode(application.into_bytes())))
        }
        ProcessedMessageContent::ProposalMessage(proposal) => {
            group.store_pending_proposal(provider.storage(), *proposal).map_err(|e| MlsError::Storage(e.to_string()))?;
            ("proposal".to_owned(), None)
        }
        ProcessedMessageContent::ExternalJoinProposalMessage(proposal) => {
            group.store_pending_proposal(provider.storage(), *proposal).map_err(|e| MlsError::Storage(e.to_string()))?;
            ("external_join_proposal".to_owned(), None)
        }
        ProcessedMessageContent::StagedCommitMessage(commit) => {
            group.merge_staged_commit(&provider, *commit).map_err(|_| MlsError::Rejected)?;
            ("commit".to_owned(), None)
        }
        ProcessedMessageContent::OwnPendingCommit => {
            group.merge_pending_commit(&provider).map_err(|_| MlsError::Rejected)?;
            ("own_commit".to_owned(), None)
        }
        ProcessedMessageContent::OwnPrivateMessage => ("own_message".to_owned(), None),
    };
    Ok(ProcessResult {
        kind,
        group_id: BASE64.encode(id_bytes),
        epoch: group.epoch().as_u64(),
        application,
    })
}

pub fn remove_member(
    database: &mut OpenDatabase,
    group_id: &[u8],
    member_identity: &[u8],
) -> Result<CommitBundle, MlsError> {
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    let indexes = group.members().filter_map(|member| {
        if member.credential.serialized_content() == member_identity {
            Some(member.index)
        } else {
            None
        }
    }).collect::<Vec<_>>();
    if indexes.len() != 1 {
        return Err(MlsError::IdentityMismatch);
    }
    let (commit, welcome, _group_info) = group.remove_members(&provider, &signer, &indexes)
        .map_err(|_| MlsError::Rejected)?;
    group.merge_pending_commit(&provider).map_err(|_| MlsError::Rejected)?;
    let commit = commit.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)?;
    let welcome = welcome.map(|m| m.tls_serialize_detached().map(|b| BASE64.encode(b)))
        .transpose().map_err(|_| MlsError::InvalidMessage)?;
    Ok(CommitBundle { commit: BASE64.encode(commit), welcome, epoch: group.epoch().as_u64() })
}

pub fn epoch(database: &mut OpenDatabase, group_id: &[u8]) -> Result<u64, MlsError> {
    let (provider, _signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    Ok(group.epoch().as_u64())
}

pub fn members(database: &mut OpenDatabase, group_id: &[u8]) -> Result<Vec<Vec<u8>>, MlsError> {
    let (provider, _signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    Ok(group.members().map(|m| m.credential.serialized_content().to_vec()).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn database(path: &str, key: &[u8]) -> OpenDatabase {
        open_database(path, key).unwrap()
    }

    #[test]
    fn two_clients_create_join_encrypt_remove_and_advance_epoch() {
        let temp = tempdir().unwrap();
        let key_a = [0x11u8; 32];
        let key_b = [0x22u8; 32];
        let mut a = database(temp.path().join("a.db").to_str().unwrap(), &key_a);
        let mut b = database(temp.path().join("b.db").to_str().unwrap(), &key_b);
        let gid = b"rly-test-group-1";
        let a_credential = b"RELYRA-MLS-V1:alice:device-a";
        let b_credential = b"RELYRA-MLS-V1:bob:device-b";

        create_group(&mut a, gid, a_credential).unwrap();
        let key_package = generate_key_package(&mut b, b_credential).unwrap();
        let bundle = add_members(&mut a, gid, &[key_package]).unwrap();
        join_group(&mut b, gid, &BASE64.decode(bundle.welcome.unwrap()).unwrap(), b_credential).unwrap();

        let app = encrypt(&mut a, gid, b"mls secret").unwrap();
        let result = process_message(&mut b, &app).unwrap();
        assert_eq!(result.kind, "application");
        assert_eq!(BASE64.decode(result.application.unwrap()).unwrap(), b"mls secret");

        let _removed = remove_member(&mut a, gid, b_credential).unwrap();
        let epoch = epoch(&mut a, gid).unwrap();
        assert_eq!(epoch, 2);
        assert!(encrypt(&mut a, gid, b"post-removal").is_ok());
        assert!(process_message(&mut b, &encrypt(&mut a, gid, b"denied").unwrap()).is_err());
    }
    #[test]
    fn group_epoch_and_application_state_recover_after_reopening_sqlcipher() {
        let temp = tempdir().unwrap();
        let path_a = temp.path().join("restart-a.db");
        let path_b = temp.path().join("restart-b.db");
        let path_a = path_a.to_str().unwrap().to_owned();
        let path_b = path_b.to_str().unwrap().to_owned();
        let key_a = [0x31u8; 32];
        let key_b = [0x42u8; 32];
        let gid = b"relyra-restart-group";
        let a_credential = b"RELYRA-MLS-V1:alice:device-a";
        let b_credential = b"RELYRA-MLS-V1:bob:device-b";

        {
            let mut a = database(&path_a, &key_a);
            let mut b = database(&path_b, &key_b);
            create_group(&mut a, gid, a_credential).unwrap();
            let key_package = generate_key_package(&mut b, b_credential).unwrap();
            let bundle = add_members(&mut a, gid, &[key_package]).unwrap();
            let welcome = BASE64.decode(bundle.welcome.unwrap()).unwrap();
            join_group(&mut b, gid, &welcome, b_credential).unwrap();
            assert_eq!(epoch(&mut a, gid).unwrap(), 1);
            assert_eq!(epoch(&mut b, gid).unwrap(), 1);
        }

        // Closing both SQLCipher connections must not discard the OpenMLS epoch or key schedule.
        let mut a = database(&path_a, &key_a);
        let mut b = database(&path_b, &key_b);
        assert_eq!(epoch(&mut a, gid).unwrap(), 1);
        assert_eq!(epoch(&mut b, gid).unwrap(), 1);

        let ciphertext = encrypt(&mut a, gid, b"recovered after process restart").unwrap();
        let opened = process_message(&mut b, &ciphertext).unwrap();
        assert_eq!(opened.kind, "application");
        assert_eq!(
            BASE64.decode(opened.application.unwrap()).unwrap(),
            b"recovered after process restart",
        );

        let _removal = remove_member(&mut a, gid, b_credential).unwrap();
        assert_eq!(epoch(&mut a, gid).unwrap(), 2);
        drop(a);
        drop(b);

        let mut recovered_owner = database(&path_a, &key_a);
        let mut recovered_removed = database(&path_b, &key_b);
        assert_eq!(epoch(&mut recovered_owner, gid).unwrap(), 2);
        let post_removal = encrypt(&mut recovered_owner, gid, b"new epoch only").unwrap();
        assert!(
            process_message(&mut recovered_removed, &post_removal).is_err(),
            "a removed member must not recover the new epoch after reopening storage",
        );
    }
}
