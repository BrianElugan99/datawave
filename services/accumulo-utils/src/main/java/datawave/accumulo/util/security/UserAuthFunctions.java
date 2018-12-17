package datawave.accumulo.util.security;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import datawave.security.authorization.DatawaveUser;
import org.apache.accumulo.core.security.Authorizations;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Function definitions for common DataWave/Accumulo security concerns, such as translation of {@link DatawaveUser} auth tokens to Accumulo
 * {@link Authorizations}, etc. Default implementation provided.
 *
 * @see datawave.accumulo.util.security.UserAuthFunctions.Default
 */
public interface UserAuthFunctions {
    
    char REQUESTED_AUTHS_DELIMITER = ',';
    
    /**
     * Typically, implementations will simply compute the intersection of the two sets, {@code requestedAuths} and {@link DatawaveUser#getAuths()}, and then
     * return the resulting set in the form of an {@link Authorizations} instance.
     * <p>
     * For example, computing the intersection of the sets can allow a DataWave user to request a "downgraded" subset of their granted auths for their pending
     * query while also preventing privilege escalation.
     * <p>
     * If {@code requestedAuths} contains elements that don't appear in {@link DatawaveUser#getAuths()}, implementors may opt to throw a runtime exception
     * indicating that authorizations have been requested which the user has not been granted.
     * <p>
     * Additionally, implementors should return a default {@link Authorizations} instance (i.e., {@code new Authorizations()}) rather than {@code null} to
     * denote that the request is invalid based on the given inputs.
     *
     * @param requestedAuths
     *            The set of requested Accumulo authorizations (comma-delimited)
     * @param user
     *            The DataWave user for whom authorizations are being requested
     *
     * @return {@link Authorizations} instance appropriate for the given inputs
     */
    Authorizations getRequestedAuthorizations(String requestedAuths, DatawaveUser user);
    
    /**
     * This method provides a merged view of the auths from the specified proxy chain and from those in {@code primaryUserAuths}, e.g., as precomputed by
     * {@link #getRequestedAuthorizations(String, DatawaveUser)} perhaps.
     *
     * <p>
     * The resulting set may be applied by DataWave at scan time to ensure that any data returned from Accumulo is suitable for any/all users in the given chain
     *
     * @param primaryUserAuths
     *            Authorizations associated with the "primary" user, i.e., the initiating user to whom scan results will ultimately be delivered
     * @param proxyChain
     *            Collection of proxies denoting the complete user chain for a given request
     *
     * @return A set of {@link Authorizations}, one per user entity represented by the user chain including {@code primaryUserAuths}
     */
    LinkedHashSet<Authorizations> mergeAuthorizations(Authorizations primaryUserAuths, Collection<? extends DatawaveUser> proxyChain);
    
    /**
     * Default implementation of {@link UserAuthFunctions}
     */
    class Default implements UserAuthFunctions {
        
        /**
         *
         * @return IFF every element in {@code requestedAuths} also exists in {@link DatawaveUser#getAuths()} then {@code requestedAuths} is translated to
         *         {@link Authorizations} and returned. If either of {@code requestedAuths} or {@link DatawaveUser#getAuths()} is empty/null, then
         *         {@link Authorizations#EMPTY} is returned. If any requested auths are missing in {@link DatawaveUser#getAuths()}, then an exception is thrown
         *
         * @throws IllegalArgumentException
         *             if {@code requestedAuths} contains elements that do not exist in {@link DatawaveUser#getAuths()}
         */
        @Override
        public Authorizations getRequestedAuthorizations(String requestedAuths, DatawaveUser user) {
            if (null == user) {
                return Authorizations.EMPTY;
            }
            return UserAuthFunctions.getRequestedAuthorizations(requestedAuths, user::getAuths, true);
        }
        
        /**
         * If the specified {@code primaryUserAuths} is {@code null}, then a set of size = 1 will will be returned, and its one element will be a default
         * Authorizations instance (i.e., {@code new Authorizations()}
         */
        @Override
        public LinkedHashSet<Authorizations> mergeAuthorizations(Authorizations primaryUserAuths, Collection<? extends DatawaveUser> proxyChain) {
            LinkedHashSet<Authorizations> mergedAuths = new LinkedHashSet<>();
            
            if (null == primaryUserAuths) {
                mergedAuths.add(Authorizations.EMPTY);
            } else {
                mergedAuths.add(primaryUserAuths);
                
                if (null != proxyChain) {
                    // Now simply add the auths from each non-primary user to the merged auths set
                    // @formatter:off
                    proxyChain.stream()
                            .map(DatawaveUser::getAuths)
                            .map(UserAuthFunctions::toAuthorizations)
                            .forEach(mergedAuths::add);
                    // @formatter:on
                }
            }
            return mergedAuths;
        }
    }
    
    /**
     * @return If {@code throwOnMissingAuths} is {@code false}, returns the intersection of {@code requestedAuths} and {@code authSupplier.get()} as an
     *         {@link Authorizations} instance. If {@code throwOnMissingAuths} is {@code true}, the same intersection is computed, but
     *         {@link IllegalArgumentException} is thrown if any auths were requested but not supplied.
     */
    static Authorizations getRequestedAuthorizations(String requestedAuths, Supplier<Collection<String>> authSupplier, boolean throwOnMissingAuths) {
        HashSet<String> requested = null;
        if (!Strings.isNullOrEmpty(requestedAuths)) {
            requested = new HashSet<>(splitAuths(requestedAuths));
        }
        
        Authorizations authorizations = null;
        
        if (null != authSupplier) {
            HashSet<String> missingAuths = (requested == null) ? new HashSet<>() : new HashSet<>(requested);
            HashSet<String> userAuths = new HashSet<>(authSupplier.get());
            if (!userAuths.isEmpty()) {
                if (null != requested) {
                    missingAuths.removeAll(userAuths);
                    userAuths.retainAll(requested);
                }
                authorizations = new Authorizations(userAuths.toArray(new String[userAuths.size()]));
            }
            
            if (!missingAuths.isEmpty() && throwOnMissingAuths) {
                throw new IllegalArgumentException("User requested authorizations that they don't have. Missing: " + missingAuths + ", Requested: " + requested
                                + ", User: " + userAuths);
            }
        }
        
        if (null == authorizations) {
            authorizations = Authorizations.EMPTY;
        }
        return authorizations;
    }
    
    /**
     * Converts comma-delimited {@code requestedAuths} to a list
     * 
     * @param requestedAuths
     *            comma-delimited list of authorizations
     * @return List containing the auth tokens from {@code requestedAuths}
     */
    static List<String> splitAuths(String requestedAuths) {
        return Arrays.asList(Iterables.toArray(Splitter.on(REQUESTED_AUTHS_DELIMITER).omitEmptyStrings().trimResults().split(requestedAuths), String.class));
    }
    
    /**
     * Converts auths string collection to an {@link Authorizations} instance
     * 
     * @param auths
     *            Auths to convert
     * @return {@link Authorizations} equivalent of the specified string collection
     */
    static Authorizations toAuthorizations(Collection<String> auths) {
        return new Authorizations(auths.stream().map(String::trim).map(s -> s.getBytes(UTF_8)).collect(Collectors.toList()));
    }
}
