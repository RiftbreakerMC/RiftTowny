pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'PUBLISH_RELEASE',
            defaultValue: false,
            description: 'Also attach the built jar to a GitHub prerelease'
        )
    }

    tools {
        jdk 'temurin-25'
        maven 'maven-3'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // Written once and reused by every Maven invocation. It holds only ${env.*}
        // references, so no token is ever written into the workspace; Maven expands them from
        // the environment that withCredentials provides around each build step.
        stage('Maven settings') {
            steps {
                writeFile file: '.jenkins-settings.xml', text: '''<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
'''
            }
        }

        // Credentials are needed to *build*, not only to publish: rifttowny-integrations
        // compiles against sibling artifacts published to GitHub Packages, and Packages
        // requires authentication even to read. Locally those resolve from the local
        // repository after `mvn install` in the sibling checkouts, so no token is needed
        // there. See BUILDING.md.
        stage('Build') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'github-riftbreaker',
                        usernameVariable: 'GITHUB_ACTOR',
                        passwordVariable: 'GITHUB_TOKEN')]) {
                    sh 'mvn --batch-mode --no-transfer-progress -s .jenkins-settings.xml clean install'
                }
            }
        }

        // Deploys rifttowny-api and friends so sibling repositories can compile the
        // RiftTowny adapters described in INTEGRATION_CONTRACTS.md.
        //
        // Only main publishes. `when { branch 'main' }` is not used: it matches only in
        // multibranch jobs, and these are plain Pipeline jobs, where it silently never
        // fires and the stage is skipped while the build still reports success.
        stage('Publish to GitHub Packages') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'github-riftbreaker',
                        usernameVariable: 'GITHUB_ACTOR',
                        passwordVariable: 'GITHUB_TOKEN')]) {
                    script {
                        sh '''
                            set -eu

                            branch="$(git for-each-ref --points-at HEAD --format='%(refname:short)' refs/remotes/origin | sed 's#^origin/##' | head -n 1)"
                            if [ "${branch}" != "main" ]; then
                              echo "Not on main (resolved '${branch}'); skipping publish."
                              exit 0
                            fi

                            # Redirected rather than piped through tee: `sh` here is /bin/sh,
                            # where PIPESTATUS does not exist, so a pipeline would report tee's
                            # exit status and every deploy failure would look like a success.
                            if mvn --batch-mode --no-transfer-progress -s .jenkins-settings.xml -DskipTests deploy > .deploy.log 2>&1; then
                              cat .deploy.log
                              exit 0
                            fi
                            cat .deploy.log

                            # A release version that is already in Packages answers 409 and is
                            # immutable. That is a no-op, not a failure; bump the version to
                            # publish changed code. Any other deploy error still fails the build.
                            if grep -q "status code: 409" .deploy.log; then
                              echo "Version already published to GitHub Packages (409); continuing."
                              exit 0
                            fi
                            echo "DEPLOY_FAILED"
                            exit 1
                        '''
                    }
                }
            }
            post {
                failure {
                    // Deferred so the release stage and the archiving below still run.
                    script { env.DEPLOY_FAILED = 'true' }
                }
            }
        }

        // Opt-in per build. Creates (or reuses) a prerelease tagged with the version and
        // short commit, then attaches the shippable jar.
        stage('Publish GitHub release') {
            when {
                expression { return params.PUBLISH_RELEASE }
            }
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'github-riftbreaker',
                        usernameVariable: 'GITHUB_ACTOR',
                        passwordVariable: 'GITHUB_TOKEN')]) {
                    sh '''
                        set -eu

                        repo="RiftbreakerMC/RiftTowny"
                        api="https://api.github.com/repos/${repo}"
                        version="$(mvn -q -s .jenkins-settings.xml -DforceStdout help:evaluate -Dexpression=project.version)"
                        sha="$(git rev-parse HEAD)"
                        short="$(git rev-parse --short HEAD)"
                        tag="rifttowny-${version}-${short}"

                        # The agent has no python3 and jq is not guaranteed, so pull the release
                        # id out with grep. GitHub pretty-prints responses as '"id": 123', so the
                        # separator must tolerate whitespace, and head runs after -o because a
                        # compact single-line response would otherwise yield the author's and
                        # each asset's id too. The trailing || true matters: looking up a release
                        # that does not exist yet is the normal first-publish path, and grep exits
                        # non-zero on no match, which under set -e would abort here.
                        extract_id() { grep -o '"id"[[:space:]]*:[[:space:]]*[0-9][0-9]*' | head -n 1 | grep -o '[0-9][0-9]*' || true; }

                        id="$(curl -sS -H "Authorization: Bearer ${GITHUB_TOKEN}" "${api}/releases/tags/${tag}" | extract_id)"

                        if [ -z "${id}" ]; then
                          printf '{"tag_name":"%s","target_commitish":"%s","name":"RiftTowny %s (%s)","body":"Automated Jenkins build.","prerelease":true}' "${tag}" "${sha}" "${version}" "${short}" > .release.json
                          code="$(curl -sS -X POST -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Content-Type: application/json" -d @.release.json "${api}/releases" -o .release-response.json -w '%{http_code}')"
                          id="$(extract_id < .release-response.json)"
                          if [ -z "${id}" ]; then
                            echo "Release creation failed with HTTP ${code}. GitHub said:" >&2
                            head -c 600 .release-response.json >&2
                            echo >&2
                          fi
                          rm -f .release.json .release-response.json
                        fi

                        if [ -z "${id}" ]; then
                          echo "Could not create or find the release ${tag}" >&2
                          exit 1
                        fi

                        jar="rifttowny-paper/target/RiftTowny.jar"
                        if [ ! -f "${jar}" ]; then
                          echo "No jar at ${jar}" >&2
                          exit 1
                        fi
                        code="$(curl -sS -X POST -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Content-Type: application/java-archive" --data-binary @"${jar}" "https://uploads.github.com/repos/${repo}/releases/${id}/assets?name=RiftTowny.jar" -o /dev/null -w '%{http_code}')"
                        echo "uploaded RiftTowny.jar -> HTTP ${code}"
                        # 422 means an asset of that name is already attached.
                        case "${code}" in 201|422) ;; *) exit 1 ;; esac
                    '''
                }
            }
        }
    }

    post {
        always {
            // allowEmptyResults, or an early build failure gets masked by the archiver
            // reporting "No test report files were found. Configuration error?".
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
            archiveArtifacts artifacts: 'rifttowny-paper/target/RiftTowny.jar', allowEmptyArchive: true, fingerprint: true
            sh 'rm -f .jenkins-settings.xml .deploy.log'
            // The deploy failure the publish stage deferred so the release could still run.
            // Applied here rather than thrown, because everything worth doing has been done
            // by now and throwing would only skip the archiving above.
            script {
                if (env.DEPLOY_FAILED == 'true') {
                    currentBuild.result = 'FAILURE'
                    echo 'Failing the build: publishing to GitHub Packages did not succeed.'
                }
            }
        }
    }
}
