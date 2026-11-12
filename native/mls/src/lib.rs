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
use std::{collections::HashSet, path::Path};
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
         );
         CREATE TABLE IF NOT EXISTS relyra_processed_mls_proposals (
             group_id BLOB NOT NULL,
             message_id TEXT NOT NULL,
             PRIMARY KEY(group_id, message_id)
         );
         CREATE TABLE IF NOT EXISTS relyra_pending_mls_commits (
             group_id BLOB PRIMARY KEY NOT NULL,
             bundle_json TEXT NOT NULL
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

#[derive(Serialize, Deserialize, Clone)]
pub struct CommitBundle {
    pub commit: String,
    pub welcome: Option<String>,
    pub epoch: u64,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct PreparedCommitBundle {
    pub commit: String,
    pub welcome: Option<String>,
    pub epoch: u64,
    pub removed_ids: Vec<String>,
    pub added_ids: Vec<String>,
    pub control_message_id: Option<String>,
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


/// Extract the account ID from RELYRA's BasicCredential framing. The credential
/// is authenticated by OpenMLS and was account-signature-verified when its
/// KeyPackage was admitted; this parser binds an incoming proposal to the
/// authenticated RELYRA envelope sender.
fn credential_public_id(encoded: &[u8]) -> Option<&str> {
    if encoded.len() > 512 || encoded.len() < 5 + 2 + 2 + 64 ||
        &encoded[..4] != b"RMLC" || encoded[4] != 1 {
        return None;
    }
    let mut offset = 5usize;
    let id_len = u16::from_be_bytes([*encoded.get(offset)?, *encoded.get(offset + 1)?]) as usize;
    offset += 2;
    let id_end = offset.checked_add(id_len)?;
    let public_id = std::str::from_utf8(encoded.get(offset..id_end)?).ok()?;
    offset = id_end;
    let device_len = u16::from_be_bytes([*encoded.get(offset)?, *encoded.get(offset + 1)?]) as usize;
    offset += 2;
    let device_end = offset.checked_add(device_len)?;
    let device_id = std::str::from_utf8(encoded.get(offset..device_end)?).ok()?;
    offset = device_end;
    if public_id.is_empty() || device_id.is_empty() || encoded.len() != offset + 64 {
        return None;
    }
    Some(public_id)
}

/// Process and store one authenticated RFC 9420 Proposal. The outer RELYRA
/// session sender is checked against the MLS BasicCredential before the
/// proposal enters OpenMLS' persistent proposal store.
pub fn process_proposal(
    database: &mut OpenDatabase,
    message: &[u8],
    message_id: &str,
    expected_public_id: &str,
    allowed_target_public_ids: &[String],
    allow_add: bool,
    allow_remove: bool,
) -> Result<u64, MlsError> {
    if message.is_empty() || message.len() > 1024 * 1024 ||
        message_id.is_empty() || message_id.len() > 128 ||
        expected_public_id.is_empty() || expected_public_id.len() > 256 ||
        allowed_target_public_ids.is_empty() || allowed_target_public_ids.len() > 200 ||
        allow_add == allow_remove {
        return Err(MlsError::InvalidMessage);
    }
    let incoming = MlsMessageIn::tls_deserialize_exact_bytes(message)
        .map_err(|_| MlsError::InvalidMessage)?;
    let protocol: ProtocolMessage = incoming.try_into_protocol_message()
        .map_err(|_| MlsError::InvalidMessage)?;
    let group_id_bytes = protocol.group_id().as_slice().to_vec();
    let id = GroupId::from_slice(&group_id_bytes);

    // The OpenMLS proposal queue and RELYRA message-id receipt share one
    // SQLCipher transaction. A process death cannot leave a queued proposal
    // that will be rejected as a replay on the sender's retry.
    database.connection.execute_batch("BEGIN IMMEDIATE").map_err(MlsError::Sql)?;
    let result = (|| -> Result<u64, MlsError> {
        let duplicate: Option<i64> = database.connection.query_row(
            "SELECT 1 FROM relyra_processed_mls_proposals WHERE group_id = ?1 AND message_id = ?2",
            rusqlite::params![group_id_bytes, message_id],
            |row| row.get(0),
        ).optional().map_err(MlsError::Sql)?;
        let (provider, _signer) = provider_with_signer(database)?;
        let mut group = MlsGroup::load(provider.storage(), &id)
            .map_err(|e| MlsError::Storage(e.to_string()))?
            .ok_or(MlsError::GroupNotFound)?;
        if !group.is_active() {
            return Err(MlsError::Inactive);
        }
        if duplicate.is_some() {
            return Ok(group.epoch().as_u64());
        }
        let processed = group.process_message(&provider, protocol)
            .map_err(|_| MlsError::InvalidMessage)?;
        if !matches!(processed.sender(), Sender::Member(_)) ||
            !credential_public_id(processed.credential().serialized_content())
                .is_some_and(|sender| sender.eq_ignore_ascii_case(expected_public_id)) {
            return Err(MlsError::IdentityMismatch);
        }
        match processed.into_content() {
            ProcessedMessageContent::ProposalMessage(proposal) => {
                let target_id = match proposal.proposal() {
                    Proposal::Add(add) if allow_add => credential_public_id(
                        add.key_package().leaf_node().credential().serialized_content()
                    ),
                    Proposal::Remove(remove) if allow_remove => group.member(remove.removed())
                        .and_then(|credential| credential_public_id(credential.serialized_content())),
                    _ => None,
                }.ok_or(MlsError::InvalidMessage)?;
                if !allowed_target_public_ids.iter().any(|id| id.eq_ignore_ascii_case(target_id)) {
                    return Err(MlsError::IdentityMismatch);
                }
                group.store_pending_proposal(provider.storage(), *proposal)
                    .map_err(|e| MlsError::Storage(e.to_string()))?;
            }
            _ => return Err(MlsError::InvalidMessage),
        }
        let epoch = group.epoch().as_u64();
        drop(group);
        drop(provider);
        database.connection.execute(
            "INSERT INTO relyra_processed_mls_proposals(group_id, message_id) VALUES(?1, ?2)",
            rusqlite::params![group_id_bytes, message_id],
        ).map_err(MlsError::Sql)?;
        Ok(epoch)
    })();
    match result {
        Ok(epoch) => {
            database.connection.execute_batch("COMMIT").map_err(MlsError::Sql)?;
            Ok(epoch)
        }
        Err(error) => {
            let _ = database.connection.execute_batch("ROLLBACK");
            Err(error)
        }
    }
}

/// Create a native RFC 9420 Add Proposal. The message is not committed until
/// an authorized committer accepts it through commit_pending_proposals().
pub fn propose_add_member(
    database: &mut OpenDatabase,
    group_id: &[u8],
    key_package_bytes: &[u8],
) -> Result<Vec<u8>, MlsError> {
    if key_package_bytes.is_empty() || key_package_bytes.len() > 64 * 1024 {
        return Err(MlsError::InvalidKeyPackage);
    }
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    let key_package = KeyPackageIn::tls_deserialize_exact_bytes(key_package_bytes)
        .map_err(|_| MlsError::InvalidKeyPackage)?
        .validate(provider.crypto(), ProtocolVersion::Mls10)
        .map_err(|_| MlsError::InvalidKeyPackage)?;
    // Use a by-value proposal so recipients can process the Commit even if they
    // were offline when the standalone proposal was first delivered to the owner.
    let (message, _proposal_ref) = group.propose(
        &provider, &signer, Propose::Add(key_package), ProposalOrRefType::Proposal,
    ).map_err(|_| MlsError::Rejected)?;
    message.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)
}

/// Create a native RFC 9420 Remove Proposal for the member carrying this
/// already-verified RELYRA account/device credential.
pub fn propose_remove_member(
    database: &mut OpenDatabase,
    group_id: &[u8],
    member_identity: &[u8],
) -> Result<Vec<u8>, MlsError> {
    let (provider, signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let mut group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    let target_credential = group.members()
        .find(|member| member.credential.serialized_content() == member_identity)
        .map(|member| member.credential)
        .ok_or(MlsError::IdentityMismatch)?;
    // By-value Remove proposal keeps Commit processing independent from whether
    // every member received this standalone proposal before going offline.
    let (message, _proposal_ref) = group.propose(
        &provider, &signer, Propose::RemoveCredential(target_credential), ProposalOrRefType::Proposal,
    ).map_err(|_| MlsError::Rejected)?;
    message.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)
}

/// Commit all validated proposals currently queued in OpenMLS. The caller is
/// responsible for enforcing the RELYRA owner/admin policy before invoking it.
pub fn prepare_pending_proposals_commit(
    database: &mut OpenDatabase,
    group_id: &[u8],
    control_message_id: Option<&str>,
) -> Result<PreparedCommitBundle, MlsError> {
    let id = GroupId::from_slice(group_id);
    database.connection.execute_batch("BEGIN IMMEDIATE").map_err(MlsError::Sql)?;
    let result = (|| -> Result<PreparedCommitBundle, MlsError> {
        let existing: Option<String> = database.connection.query_row(
            "SELECT bundle_json FROM relyra_pending_mls_commits WHERE group_id = ?1",
            [group_id],
            |row| row.get(0),
        ).optional().map_err(MlsError::Sql)?;
        if let Some(json) = existing {
            return serde_json::from_str(&json).map_err(|_| MlsError::InvalidMessage);
        }
        let (provider, signer) = provider_with_signer(database)?;
        let mut group = MlsGroup::load(provider.storage(), &id)
            .map_err(|e| MlsError::Storage(e.to_string()))?
            .ok_or(MlsError::GroupNotFound)?;
        if !group.is_active() || group.pending_commit().is_some() || !group.has_pending_proposals() {
            return Err(MlsError::Rejected);
        }
        // Resolve identical concurrent admin actions deterministically. The first
        // proposal in OpenMLS queue order wins; redundant Add/Remove proposals for
        // the same RELYRA identity are removed before constructing the Commit.
        let mut seen = HashSet::new();
        let duplicate_refs = group.pending_proposals().filter_map(|queued| {
            let target = match queued.proposal() {
                Proposal::Add(add) => credential_public_id(
                    add.key_package().leaf_node().credential().serialized_content()
                ).map(|value| format!("add:{}", value.to_ascii_lowercase())),
                Proposal::Remove(remove) => group.member(remove.removed())
                    .and_then(|credential| credential_public_id(credential.serialized_content()))
                    .map(|value| format!("remove:{}", value.to_ascii_lowercase())),
                _ => None,
            }?;
            if seen.insert(target) { None } else { Some(queued.proposal_reference_ref().clone()) }
        }).collect::<Vec<_>>();
        for proposal_ref in duplicate_refs {
            group.remove_pending_proposal(provider.storage(), &proposal_ref)
                .map_err(|e| MlsError::Storage(e.to_string()))?;
        }
        if !group.has_pending_proposals() {
            return Err(MlsError::Rejected);
        }
        let (commit, welcome, _group_info) = group.commit_to_pending_proposals(&provider, &signer)
            .map_err(|_| MlsError::Rejected)?;
        let staged = group.pending_commit().ok_or(MlsError::Rejected)?;
        let removed_ids = staged.remove_proposals().filter_map(|queued| {
            group.member(queued.remove_proposal().removed())
                .and_then(|credential| credential_public_id(credential.serialized_content()))
                .map(str::to_owned)
        }).collect::<Vec<_>>();
        let added_ids = staged.add_proposals().filter_map(|queued| {
            credential_public_id(
                queued.add_proposal().key_package().leaf_node().credential().serialized_content()
            ).map(str::to_owned)
        }).collect::<Vec<_>>();
        let bundle = PreparedCommitBundle {
            commit: BASE64.encode(commit.tls_serialize_detached().map_err(|_| MlsError::InvalidMessage)?),
            welcome: welcome.map(|message| message.tls_serialize_detached().map(|bytes| BASE64.encode(bytes)))
                .transpose().map_err(|_| MlsError::InvalidMessage)?,
            epoch: group.epoch().as_u64() + 1,
            removed_ids,
            added_ids,
            control_message_id: control_message_id.map(str::to_owned),
        };
        let json = serde_json::to_string(&bundle).map_err(|_| MlsError::InvalidMessage)?;
        drop(group);
        drop(provider);
        database.connection.execute(
            "INSERT INTO relyra_pending_mls_commits(group_id, bundle_json) VALUES(?1, ?2)",
            rusqlite::params![group_id, json],
        ).map_err(MlsError::Sql)?;
        Ok(bundle)
    })();
    match result {
        Ok(bundle) => {
            database.connection.execute_batch("COMMIT").map_err(MlsError::Sql)?;
            Ok(bundle)
        }
        Err(error) => {
            let _ = database.connection.execute_batch("ROLLBACK");
            Err(error)
        }
    }
}

pub fn pending_commit_info(
    database: &mut OpenDatabase,
    group_id: &[u8],
) -> Result<Option<PreparedCommitBundle>, MlsError> {
    let json: Option<String> = database.connection.query_row(
        "SELECT bundle_json FROM relyra_pending_mls_commits WHERE group_id = ?1",
        [group_id],
        |row| row.get(0),
    ).optional().map_err(MlsError::Sql)?;
    json.map(|value| serde_json::from_str(&value).map_err(|_| MlsError::InvalidMessage)).transpose()
}

pub fn merge_pending_commit(database: &mut OpenDatabase, group_id: &[u8]) -> Result<u64, MlsError> {
    let id = GroupId::from_slice(group_id);
    database.connection.execute_batch("BEGIN IMMEDIATE").map_err(MlsError::Sql)?;
    let result = (|| -> Result<u64, MlsError> {
        let (provider, _signer) = provider_with_signer(database)?;
        let mut group = MlsGroup::load(provider.storage(), &id)
            .map_err(|e| MlsError::Storage(e.to_string()))?
            .ok_or(MlsError::GroupNotFound)?;
        if group.pending_commit().is_some() {
            group.merge_pending_commit(&provider).map_err(|_| MlsError::Rejected)?;
        }
        let epoch = group.epoch().as_u64();
        drop(group);
        drop(provider);
        database.connection.execute(
            "DELETE FROM relyra_pending_mls_commits WHERE group_id = ?1",
            [group_id],
        ).map_err(MlsError::Sql)?;
        Ok(epoch)
    })();
    match result {
        Ok(epoch) => {
            database.connection.execute_batch("COMMIT").map_err(MlsError::Sql)?;
            Ok(epoch)
        }
        Err(error) => {
            let _ = database.connection.execute_batch("ROLLBACK");
            Err(error)
        }
    }
}

pub fn commit_pending_proposals(
    database: &mut OpenDatabase,
    group_id: &[u8],
) -> Result<CommitBundle, MlsError> {
    let prepared = prepare_pending_proposals_commit(database, group_id, None)?;
    let epoch = merge_pending_commit(database, group_id)?;
    Ok(CommitBundle { commit: prepared.commit, welcome: prepared.welcome, epoch })
}

pub fn pending_proposal_count(database: &mut OpenDatabase, group_id: &[u8]) -> Result<usize, MlsError> {
    let (provider, _signer) = provider_with_signer(database)?;
    let id = GroupId::from_slice(group_id);
    let group = MlsGroup::load(provider.storage(), &id)
        .map_err(|e| MlsError::Storage(e.to_string()))?
        .ok_or(MlsError::GroupNotFound)?;
    Ok(group.pending_proposals().count())
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
    fn test_credential(public_id: &str, device_id: &str) -> Vec<u8> {
        let mut value = b"RMLC".to_vec();
        value.push(1);
        value.extend_from_slice(&(public_id.len() as u16).to_be_bytes());
        value.extend_from_slice(public_id.as_bytes());
        value.extend_from_slice(&(device_id.len() as u16).to_be_bytes());
        value.extend_from_slice(device_id.as_bytes());
        value.extend_from_slice(&[0u8; 64]);
        value
    }

    #[test]
    fn native_add_proposal_is_stored_committed_and_joined_by_welcome() {
        let temp = tempdir().unwrap();
        let mut owner = database(temp.path().join("owner.db").to_str().unwrap(), &[0x51; 32]);
        let mut invitee = database(temp.path().join("invitee.db").to_str().unwrap(), &[0x52; 32]);
        let gid = b"rfc9420-add-proposal";
        let owner_credential = test_credential("alice", "00000000-0000-4000-8000-000000000001");
        let invitee_credential = test_credential("bob", "00000000-0000-4000-8000-000000000002");

        create_group(&mut owner, gid, &owner_credential).unwrap();
        let key_package = generate_key_package(&mut invitee, &invitee_credential).unwrap();
        let proposal = propose_add_member(&mut owner, gid, &key_package).unwrap();
        assert_eq!(process_proposal(&mut owner, &proposal, "proposal-add-1", "alice", &["bob".to_owned()], true, false).unwrap(), 0);
        // Re-delivery after a lost application ACK must not double-store or reject
        // the already accepted native proposal.
        assert_eq!(process_proposal(&mut owner, &proposal, "proposal-add-1", "alice", &["bob".to_owned()], true, false).unwrap(), 0);
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 1);

        let committed = commit_pending_proposals(&mut owner, gid).unwrap();
        assert_eq!(committed.epoch, 1);
        let welcome = BASE64.decode(committed.welcome.unwrap()).unwrap();
        join_group(&mut invitee, gid, &welcome, &invitee_credential).unwrap();
        assert_eq!(members(&mut owner, gid).unwrap().len(), 2);
        assert_eq!(members(&mut invitee, gid).unwrap().len(), 2);
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 0);
    }

    #[test]
    fn multiple_admin_remove_proposals_from_one_epoch_are_committed_together() {
        let temp = tempdir().unwrap();
        let mut owner = database(temp.path().join("owner.db").to_str().unwrap(), &[0x61; 32]);
        let mut admin_b = database(temp.path().join("admin-b.db").to_str().unwrap(), &[0x62; 32]);
        let mut admin_c = database(temp.path().join("admin-c.db").to_str().unwrap(), &[0x63; 32]);
        let gid = b"rfc9420-multi-admin-proposals";
        let owner_credential = test_credential("alice", "00000000-0000-4000-8000-000000000011");
        let admin_b_credential = test_credential("bob", "00000000-0000-4000-8000-000000000012");
        let admin_c_credential = test_credential("carol", "00000000-0000-4000-8000-000000000013");

        create_group(&mut owner, gid, &owner_credential).unwrap();
        let b_package = generate_key_package(&mut admin_b, &admin_b_credential).unwrap();
        let b_join = add_members(&mut owner, gid, &[b_package]).unwrap();
        join_group(
            &mut admin_b, gid, &BASE64.decode(b_join.welcome.unwrap()).unwrap(), &admin_b_credential,
        ).unwrap();

        let c_package = generate_key_package(&mut admin_c, &admin_c_credential).unwrap();
        let c_join = add_members(&mut owner, gid, &[c_package]).unwrap();
        let c_commit = BASE64.decode(c_join.commit).unwrap();
        assert_eq!(process_message(&mut admin_b, &c_commit).unwrap().kind, "commit");
        join_group(
            &mut admin_c, gid, &BASE64.decode(c_join.welcome.unwrap()).unwrap(), &admin_c_credential,
        ).unwrap();

        assert_eq!(epoch(&mut owner, gid).unwrap(), 2);
        assert_eq!(epoch(&mut admin_b, gid).unwrap(), 2);
        assert_eq!(epoch(&mut admin_c, gid).unwrap(), 2);

        let remove_c = propose_remove_member(&mut admin_b, gid, &admin_c_credential).unwrap();
        let remove_b = propose_remove_member(&mut admin_c, gid, &admin_b_credential).unwrap();
        assert_eq!(process_proposal(&mut owner, &remove_c, "proposal-remove-b", "bob", &["carol".to_owned()], false, true).unwrap(), 2);
        assert_eq!(process_proposal(&mut owner, &remove_b, "proposal-remove-c", "carol", &["bob".to_owned()], false, true).unwrap(), 2);
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 2);

        let committed = commit_pending_proposals(&mut owner, gid).unwrap();
        assert_eq!(committed.epoch, 3);
        let commit = BASE64.decode(committed.commit).unwrap();
        assert_eq!(process_message(&mut admin_b, &commit).unwrap().kind, "commit");
        assert_eq!(process_message(&mut admin_c, &commit).unwrap().kind, "commit");
        assert_eq!(members(&mut owner, gid).unwrap().len(), 1);
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 0);

        let post_commit = encrypt(&mut owner, gid, b"only the owner remains").unwrap();
        assert!(process_message(&mut admin_b, &post_commit).is_err());
        assert!(process_message(&mut admin_c, &post_commit).is_err());
    }

    #[test]
    fn duplicate_remove_proposals_are_deduplicated_before_the_owner_commit() {
        let temp = tempdir().unwrap();
        let mut owner = database(temp.path().join("owner.db").to_str().unwrap(), &[0x71; 32]);
        let mut admin = database(temp.path().join("admin.db").to_str().unwrap(), &[0x72; 32]);
        let mut target = database(temp.path().join("target.db").to_str().unwrap(), &[0x73; 32]);
        let gid = b"rfc9420-duplicate-remove";
        let owner_credential = test_credential("alice", "00000000-0000-4000-8000-000000000021");
        let admin_credential = test_credential("bob", "00000000-0000-4000-8000-000000000022");
        let target_credential = test_credential("carol", "00000000-0000-4000-8000-000000000023");

        create_group(&mut owner, gid, &owner_credential).unwrap();
        let admin_package = generate_key_package(&mut admin, &admin_credential).unwrap();
        let admin_join = add_members(&mut owner, gid, &[admin_package]).unwrap();
        join_group(&mut admin, gid, &BASE64.decode(admin_join.welcome.unwrap()).unwrap(), &admin_credential).unwrap();
        let target_package = generate_key_package(&mut target, &target_credential).unwrap();
        let target_join = add_members(&mut owner, gid, &[target_package]).unwrap();
        let target_commit = BASE64.decode(target_join.commit).unwrap();
        process_message(&mut admin, &target_commit).unwrap();
        join_group(&mut target, gid, &BASE64.decode(target_join.welcome.unwrap()).unwrap(), &target_credential).unwrap();

        let admin_proposal = propose_remove_member(&mut admin, gid, &target_credential).unwrap();
        let owner_proposal = propose_remove_member(&mut owner, gid, &target_credential).unwrap();
        process_proposal(&mut owner, &admin_proposal, "duplicate-from-admin", "bob", &["carol".to_owned()], false, true).unwrap();
        process_proposal(&mut owner, &owner_proposal, "duplicate-from-owner", "alice", &["carol".to_owned()], false, true).unwrap();
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 2);

        let committed = commit_pending_proposals(&mut owner, gid).unwrap();
        assert_eq!(committed.epoch, 3);
        let commit = BASE64.decode(committed.commit).unwrap();
        process_message(&mut admin, &commit).unwrap();
        process_message(&mut target, &commit).unwrap();
        assert_eq!(members(&mut owner, gid).unwrap().len(), 2);
        assert_eq!(pending_proposal_count(&mut owner, gid).unwrap(), 0);
    }

    #[test]
    fn staged_commit_bundle_survives_restart_and_merges_once() {
        let temp = tempdir().unwrap();
        let owner_path = temp.path().join("owner.db");
        let owner_key = [0x81; 32];
        let mut owner = database(owner_path.to_str().unwrap(), &owner_key);
        let mut member = database(temp.path().join("member.db").to_str().unwrap(), &[0x82; 32]);
        let gid = b"rfc9420-staged-commit-recovery";
        let owner_credential = test_credential("alice", "00000000-0000-4000-8000-000000000031");
        let member_credential = test_credential("bob", "00000000-0000-4000-8000-000000000032");

        create_group(&mut owner, gid, &owner_credential).unwrap();
        let key_package = generate_key_package(&mut member, &member_credential).unwrap();
        let joined = add_members(&mut owner, gid, &[key_package]).unwrap();
        join_group(&mut member, gid, &BASE64.decode(joined.welcome.unwrap()).unwrap(), &member_credential).unwrap();

        let proposal = propose_remove_member(&mut owner, gid, &member_credential).unwrap();
        process_proposal(
            &mut owner, &proposal, "staged-remove-1", "alice", &["bob".to_owned()], false, true,
        ).unwrap();
        let prepared = prepare_pending_proposals_commit(&mut owner, gid).unwrap();
        assert_eq!(prepared.epoch, 2);
        assert_eq!(prepared.removed_ids, vec!["bob".to_owned()]);
        assert_eq!(epoch(&mut owner, gid).unwrap(), 1);
        assert!(pending_commit_info(&mut owner, gid).unwrap().is_some());

        drop(owner);
        let mut reopened = database(owner_path.to_str().unwrap(), &owner_key);
        let recovered = pending_commit_info(&mut reopened, gid).unwrap().unwrap();
        assert_eq!(recovered.commit, prepared.commit);
        assert_eq!(recovered.epoch, prepared.epoch);
        assert_eq!(merge_pending_commit(&mut reopened, gid).unwrap(), 2);
        assert_eq!(merge_pending_commit(&mut reopened, gid).unwrap(), 2);
        assert!(pending_commit_info(&mut reopened, gid).unwrap().is_none());
        assert_eq!(members(&mut reopened, gid).unwrap().len(), 1);
    }

}
